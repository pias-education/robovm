package org.robovm.debugger;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.robovm.compiler.clazz.Clazz;
import org.robovm.compiler.clazz.ClazzInfo;
import org.robovm.compiler.clazz.Clazzes;
import org.robovm.compiler.clazz.LocalVariableInfo;
import org.robovm.compiler.clazz.LocalVariableInfo.Type;
import org.robovm.compiler.config.Arch;
import org.robovm.compiler.clazz.MethodInfo;
import org.robovm.debugger.RobovmDebuggerClient.ReadMemoryCommand;
import org.robovm.debugger.RobovmDebuggerClient.ReadStringCommand;

public class ReadStackVariablesCommand {
	private RobovmDebuggerClient debugger;
	private SuspendedStack currentStack;
	
	private ReadMemoryCommand readMethodNameAddrCommand;
	private ReadMemoryCommand readMethodDescAddrCommand;
	private ReadStringCommand readMethodNameCommand;
	private ReadStringCommand readMethodDescCommand;
	
	private String methodName;
	private String methodDescr;
	
	
	public ReadStackVariablesCommand(SuspendedStack currentStack, RobovmDebuggerClient debugger) {
		this.debugger = debugger;
		this.currentStack = currentStack;
	}
	
	public void start() {
		if (currentStack == null || currentStack.getStackFrames().size() == 0) {
			this.debugger.sendErrorToListeners("Error when trying to read stack variables, no stackframes present!");
			return;
		}
		
		String className = currentStack.getStackFrames().get(0).className;
		
		final Clazzes clazzes = debugger.getConfig().getClazzes();
		final Clazz clazz = clazzes.load(className);

		final long methodPointer = currentStack.getStackFrames().get(0).methodPointer;
		
		
		/*
		 * Serially executed:
		 * 1. read mem address for method name cstring
		 * 2. read mem address for method descr cstring
		 * 3. read method name cstring
		 * 4. read method descr cstring
		 * 
		 */
		readMethodNameAddrCommand = new ReadMemoryCommand(methodPointer + 16, 8); //method struct position, see types.h
		debugger.addListener(new RobovmDebuggerClientHandler() {
			private long methodNameAddr;
			private long methodDescAddr;
			
			@Override
			public void readMemoryCommand(ReadMemoryCommand command) {
				if (command.requestId == readMethodNameAddrCommand.requestId) {
					methodNameAddr = ByteBuffer.wrap(command.getResponse()).getLong();
					
					readMethodDescAddrCommand = new ReadMemoryCommand(methodPointer + 24, 8);
					debugger.queueCommand(readMethodDescAddrCommand);	
				}
				else if (command.requestId == readMethodDescAddrCommand.requestId) {
					methodDescAddr = ByteBuffer.wrap(command.getResponse()).getLong();
					
					readMethodNameCommand = new ReadStringCommand(methodNameAddr); 
					debugger.queueCommand(readMethodNameCommand);
				}

			}
			
			@Override
			public void readStringCommand(ReadStringCommand command) {				
				if (command.requestId == readMethodNameCommand.requestId) {
					methodName = command.getResponse();
					
					readMethodDescCommand = new ReadStringCommand(methodDescAddr);
					debugger.queueCommand(readMethodDescCommand);
				}
				else if (command.requestId == readMethodDescCommand.requestId) {
					methodDescr = command.getResponse();
					debugger.removeListener(this);
					
					final MethodInfo methodInfo = clazz.getClazzInfo().getMethod(methodName, methodDescr);
					final ReadStackVariablesHandler nextHandler = new ReadStackVariablesHandler(debugger, currentStack, clazz, methodInfo);
					debugger.addListener(nextHandler);
					nextHandler.readNextStackVariable();
				}
			}
		});
		
		debugger.queueCommand(readMethodNameAddrCommand);
		
	}
	
	//Unfinished!
	private static class ReadStackVariablesHandler extends RobovmDebuggerClientHandler {
		private MethodInfo methodInfo;
		private List<LocalVariableInfo> variablesToRead;
		private RobovmDebuggerClient debugger;
		private ReadMemoryCommand readStackVariableAddrCmd;
		private ReadMemoryCommand readStackVariableValueCmd;
		private LocalVariableInfo curStackVariable;
		private StackFrame currentStackFrame;
		private int stackVariableIndex;
		
		private List<Long> stackVariableAddr;
		private boolean addrRead;
		private long stackAddrArrayAddr;
		
		public ReadStackVariablesHandler(RobovmDebuggerClient debugger, SuspendedStack currentStack, Clazz clazz, MethodInfo methodInfo) {
			this.methodInfo = methodInfo;
			this.debugger = debugger;
			this.variablesToRead = new ArrayList<>(methodInfo.getLocalVariables());
			this.currentStackFrame = currentStack.getStackFrames().get(0);
			this.stackVariableIndex = 0;
			this.stackVariableAddr = new ArrayList<>();
			this.addrRead = false;
			
			this.stackAddrArrayAddr = debugger.getSymbolAddress("[J]" + clazz.getClassName() + "." + methodInfo.getName() + methodInfo.getDesc() + "[stackaddr]");
		}
		
		public void readNextStackVariable() {
			if (this.variablesToRead.size() > stackVariableIndex && !this.addrRead) {
				curStackVariable = this.variablesToRead.get(stackVariableIndex);
								
				readStackVariableAddrCmd = new ReadMemoryCommand(this.stackAddrArrayAddr + stackVariableIndex * 8, 8);
				stackVariableIndex++;
				debugger.queueCommand(readStackVariableAddrCmd);
				
				/*if (curStackVariable.getScopeStartLine() <= currentStackFrame.lineNumber && curStackVariable.getScopeEndLine() >= currentStackFrame.lineNumber) {					
					readStackVariableCmd = new ReadMemoryCommand(stackVariableAddress - curStackVariable.getMemoryOffset(), curStackVariable.getSize());
					debugger.queueCommand(readStackVariableCmd);
				}
				else {
					readNextStackVariable();
				}*/
			}
			else if (!this.addrRead && this.variablesToRead.size() <= stackVariableIndex) {
				this.addrRead = true;
				stackVariableIndex = 0;
				readNextStackVariable();
			}
			else if (this.variablesToRead.size() > stackVariableIndex) {
				if (this.stackVariableAddr.get(stackVariableIndex) > 0) {
					curStackVariable = this.variablesToRead.get(stackVariableIndex);
					readStackVariableValueCmd = new ReadMemoryCommand(this.stackVariableAddr.get(stackVariableIndex), curStackVariable.getSize());
					debugger.queueCommand(readStackVariableValueCmd);
					stackVariableIndex++;
				}
				else {
					stackVariableIndex++;
					readNextStackVariable();
				}
			}
		}
		
		@Override
		public void readMemoryCommand(ReadMemoryCommand command) {
			if (readStackVariableAddrCmd.requestId == command.requestId) {
				
				final ByteBuffer bb = ByteBuffer.wrap(command.response);
				this.stackVariableAddr.add(bb.getLong());
				
				/*
				LocalVariableValue localVal = new LocalVariableValue(curStackVariable);				
				final ByteBuffer bb = ByteBuffer.wrap(command.response);
								
				if (curStackVariable.getType() == Type.INT) {
				    localVal.setValue(bb.getInt(0));
				}
				else if (curStackVariable.getType() == Type.OBJECT) {
					//localVal.setValue(String.format("0x%06X", bb.getInt() & 0xFFFFFF));
					//localVal.setValue(bb.getInt(0));
				}
				else if (curStackVariable.getType() == Type.LONG) {
					localVal.setValue(bb.getLong(0));
				}
				else if (curStackVariable.getType() == Type.DOUBLE) {
					try {
						localVal.setValue(bb.getDouble(0));
					}
					catch (BufferUnderflowException e) {
						localVal.setValue("Error when reading double value.");
					}
				}
			
				for (RobovmDebuggerClientListener l : debugger.getListeners()) {
			    	l.readStackVariableCommand(localVal);
			    }
				*/
				readNextStackVariable();	
			}
			else if (readStackVariableValueCmd.requestId == command.requestId) {
				try {
					final ByteBuffer bb = ByteBuffer.wrap(command.response);
					LocalVariableValue localVal = new LocalVariableValue(curStackVariable);				
									
					if (curStackVariable.getType() == Type.INT) {
					    localVal.setValue(bb.getInt(0));
					}
					else if(curStackVariable.getType() == Type.FLOAT) {
						localVal.setValue(bb.getFloat(0));
					}
					else if (curStackVariable.getType() == Type.OBJECT) {
						//localVal.setValue(String.format("0x%06X", bb.getInt() & 0xFFFFFF));
						//localVal.setValue(bb.getInt(0));
					}
					else if (curStackVariable.getType() == Type.LONG) {
						localVal.setValue(bb.getLong(0));
					}
					else if (curStackVariable.getType() == Type.DOUBLE) {
						localVal.setValue(bb.getDouble(0));
					}
				
					for (RobovmDebuggerClientListener l : debugger.getListeners()) {
				    	l.readStackVariableCommand(localVal);
				    }
				}
				catch(RuntimeException e) {
					e.printStackTrace();
				}
				
				readNextStackVariable();
			}
		} 
	}
	
}