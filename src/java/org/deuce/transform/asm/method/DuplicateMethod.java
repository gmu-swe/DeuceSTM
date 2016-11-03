package org.deuce.transform.asm.method;

import org.deuce.objectweb.asm.Label;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.objectweb.asm.commons.AnalyzerAdapter;
import org.deuce.objectweb.asm.commons.Method;
import org.deuce.objectweb.asm.tree.FrameNode;
import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transform.asm.ClassTransformer;
import org.deuce.transform.asm.ExcludeIncludeStore;
import org.deuce.transform.asm.FieldsHolder;
import org.deuce.transform.util.Util;

import static org.deuce.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Responsible for creating the mirror version for the original 
 * method that includes instrumentation.
 *  
 * @author Guy Korland
 */
public class DuplicateMethod extends MethodVisitor {

	final static public String LOCAL_VARIBALE_NAME = "__transactionContext__";

	private final int argumentsSize;
	private final FieldsHolder fieldsHolder;
	
	private Label firstLabel;
	private Label lastLabel;
	private boolean addContextToTable = false;
	private AnalyzerAdapter analyzerAdapter;
	private final boolean addFrames;
	
	public DuplicateMethod(MethodVisitor mv, boolean isstatic, Method newMethod, FieldsHolder fieldsHolder, boolean addFrames) {
		super(Opcodes.ASM5, mv);
		this.fieldsHolder = fieldsHolder;
		this.argumentsSize = Util.calcArgumentsSize( isstatic, newMethod); 
		this.addFrames = addFrames;
	}
	
	public void setAnalyzer(AnalyzerAdapter analyzerAdapter) {
		this.analyzerAdapter = analyzerAdapter;
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name,
			String desc, boolean isItfc) 
	{
		if( ExcludeIncludeStore.exclude(owner))
		{
			super.visitMethodInsn(opcode, owner, name, desc, isItfc); // ... = foo( ...
		}
		else
		{
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			Method newMethod = ClassTransformer.createNewMethod(name, desc);
			super.visitMethodInsn(opcode, owner, name, newMethod.getDescriptor(), isItfc); // ... = foo( ...
		}
	}
	public static Object[] removeLongsDoubleTopVal(List<Object> in, Object extra) {
		ArrayList<Object> ret = new ArrayList<Object>();
		boolean lastWas2Word = false;
		for (Object n : in) {
			if (n == Opcodes.TOP && lastWas2Word) {
				//nop
			} else
				ret.add(n);
			if (n == Opcodes.DOUBLE || n == Opcodes.LONG)
				lastWas2Word = true;
			else
				lastWas2Word = false;
		}
		if(extra != null)
			ret.add(extra);
		return ret.toArray();
	}
	public FrameNode getCurrentFrameNode()
	{
		if(!addFrames)
			return new FrameNode(0, 0, new Object[0], 0, new Object[0]);
		Object[] locals = removeLongsDoubleTopVal(analyzerAdapter.locals, "org/deuce/transaction/Context");
		Object[] stack = removeLongsDoubleTopVal(analyzerAdapter.stack, null);
		FrameNode ret = new FrameNode(Opcodes.F_FULL, locals.length, locals, stack.length, stack);
		ret.type = Opcodes.F_NEW;
		return ret;
	}
	public static Object getStackTypeForType(Type t) {
		switch (t.getSort()) {
		case Type.INT:
		case Type.BOOLEAN:
		case Type.BYTE:
		case Type.CHAR:
		case Type.SHORT:
			return Opcodes.INTEGER;
		case Type.FLOAT:
			return Opcodes.FLOAT;
		case Type.DOUBLE:
			return Opcodes.DOUBLE;
		case Type.LONG:
			return Opcodes.LONG;
		case Type.ARRAY:
			return t.getDescriptor();
		case Type.OBJECT:
			return t.getInternalName();
		default:
			throw new UnsupportedOperationException(t.toString());
		}
	}
	/**
	 * Adds for each field visited a call to the context.
	 */
	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		if( ExcludeIncludeStore.exclude( owner) || 
				name.contains("$")){ // Syntactic TODO remove this limitation
			super.visitFieldInsn(opcode, owner, name, desc); // ... = foo( ...
			return;
		}

		FrameNode topFn = getCurrentFrameNode();
		LinkedList<Object> stackWhenDone = new LinkedList<Object>(topFn.stack);
		String fieldsHolderName = fieldsHolder.getFieldsHolderName(owner);
		mv.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name), "J");
		Label l1 = new Label();
		mv.visitInsn(LCONST_0);
		mv.visitInsn(LCMP);
		mv.visitJumpInsn(IFGE, l1);
		super.visitFieldInsn(opcode, owner, name, desc);
		Label l2 = new Label();
		mv.visitJumpInsn(GOTO, l2);
		mv.visitLabel(l1);
		
		if(addFrames)
			topFn.accept(mv);
		final Type type = Type.getType(desc);
		switch( opcode) {
		case GETFIELD:  //	ALOAD 0: this (stack status)
			
			addBeforeReadCall(fieldsHolderName, name);
			
			super.visitInsn(DUP);
			super.visitFieldInsn(opcode, owner, name, desc);
			super.visitFieldInsn( GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.READ_METHOD_NAME, ContextDelegator.getReadMethodDesc(type), false);
			
			if( type.getSort() >= Type.ARRAY) // non primitive
				super.visitTypeInsn( CHECKCAST, Type.getType(desc).getInternalName());
			if (addFrames) {
				stackWhenDone.pop();
				stackWhenDone.add(getStackTypeForType(type));
			}
			break;
		case PUTFIELD:
			super.visitFieldInsn( GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.WRITE_METHOD_NAME, ContextDelegator.getWriteMethodDesc(type), false);
			if (addFrames) {
				stackWhenDone.pop();
				stackWhenDone.pop();
			}
			break;
		case GETSTATIC: // check support for static fields
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, 
					StaticMethodTransformer.CLASS_BASE, "Ljava/lang/Object;");
			
			addBeforeReadCall(fieldsHolderName, name);
			
			super.visitFieldInsn(opcode, owner, name, desc);
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name) , "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.READ_METHOD_NAME, ContextDelegator.getReadMethodDesc(type), false);
			
			if( type.getSort() >= Type.ARRAY) // non primitive
				super.visitTypeInsn( CHECKCAST, Type.getType(desc).getInternalName());
			stackWhenDone.add(getStackTypeForType(type));

			break;
		case PUTSTATIC:
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, 
					StaticMethodTransformer.CLASS_BASE, "Ljava/lang/Object;");
			super.visitFieldInsn(GETSTATIC, fieldsHolderName, Util.getAddressField(name), "J");
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn(INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.STATIC_WRITE_METHOD_NAME, ContextDelegator.getStaticWriteMethodDesc(type), false);
			if (addFrames) {
				stackWhenDone.pop();
			}
			break;
		default:
			throw new UnsupportedOperationException();
		}
		mv.visitLabel(l2);
		if(addFrames)
		{
			FrameNode whenDone = new FrameNode(F_NEW, topFn.local.size(), topFn.local.toArray(), stackWhenDone.size(), stackWhenDone.toArray());
			whenDone.accept(mv);
		}
	}

	private void addBeforeReadCall(String owner, String name) {
		super.visitInsn(DUP);
		super.visitFieldInsn( GETSTATIC, owner, Util.getAddressField(name) , "J");
		super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
		super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
				ContextDelegator.BEFORE_READ_METHOD_NAME, ContextDelegator.BEFORE_READ_METHOD_DESC, false);
	}

	/**
	 * Adds for each array cell visited a call to the context
	 */
	@Override
	public void visitInsn(int opcode) {
		boolean load = false;
		boolean store = false;
		String desc = null;
		String arrayMemeberType = null;
		switch( opcode) {
		
		case AALOAD:
			// handle Object[] arrays type, the type before the last is the array. 
			// The substring removes the '[' from the array type
			String arrayType = 
				(String)this.analyzerAdapter.stack.get(this.analyzerAdapter.stack.size() - 2);
			arrayMemeberType = getArrayMemberType( arrayType);
			
			desc = ContextDelegator.READ_ARRAY_METHOD_OBJ_DESC;
			load = true;
			break;
		case BALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_BYTE_DESC;
			load = true;
			break;
		case CALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_CHAR_DESC;
			load = true;
			break;
		case SALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_SHORT_DESC;
			load = true;
			break;
		case IALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_INT_DESC;
			load = true;
			break;
		case LALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_LONG_DESC;
			load = true;
			break;
		case FALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_FLOAT_DESC;
			load = true;
			break;
		case DALOAD:
			desc = ContextDelegator.READ_ARRAY_METHOD_DOUBLE_DESC;
			load = true;
			break;
			
		case AASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_OBJ_DESC;
			store = true;
			break;
		case BASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_BYTE_DESC;
			store = true;
			break;
		case CASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_CHAR_DESC;
			store = true;
			break;
		case SASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_SHORT_DESC;
			store = true;
			break;
		case IASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_INT_DESC;
			store = true;
			break;
		case LASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_LONG_DESC;
			store = true;
			break;
		case FASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_FLOAT_DESC;
			store = true;
			break;
		case DASTORE:
			desc = ContextDelegator.WRITE_ARRAY_METHOD_DOUBLE_DESC;
			store = true;
			break;
		}
			
		if( load)
		{
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.READ_ARR_METHOD_NAME, desc, false);

			if( opcode == AALOAD){ // non primitive array need cast
				super.visitTypeInsn( CHECKCAST, arrayMemeberType);
			}
		}
		else if( store)
		{
			super.visitVarInsn(ALOAD, argumentsSize - 1); // load context
			super.visitMethodInsn( INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL,
					ContextDelegator.WRITE_ARR_METHOD_NAME, desc, false);
		}
		else{
			super.visitInsn(opcode);
		}
	}

	@Override
	public void visitIincInsn(int var, int increment) {
		super.visitIincInsn( newIndex(var), increment); // increase index due to context
	}

	@Override
	public void visitLabel(Label label) {
		if( firstLabel == null)
			firstLabel = label;
		lastLabel = label;
		super.visitLabel(label);
	}

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start,
			Label end, int index) {
		if( this.argumentsSize >  index + 1) // argument
		{
			super.visitLocalVariable(name, desc, signature, start, end, index); // non static method has this
			return;
		}
		// add context as last argument
		// the first local variable and was never added before
		if( this.argumentsSize ==  index + 1 && !addContextToTable) 
		{
			addContextToTable = true;
			super.visitLocalVariable(LOCAL_VARIBALE_NAME, Context.CONTEXT_DESC, null,
					firstLabel, lastLabel, index);
		}

		// increase all the locals index
		super.visitLocalVariable(name, desc, signature, start, end, index + 1);
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
		super.visitMaxs(maxStack + 3, maxLocals + 1);
	}

	@Override
	public void visitVarInsn(int opcode, int var) {	
		// increase the local variable index by 1
		super.visitVarInsn(opcode, newIndex(var));  
	}
	
	/**
	 * Calculate the new local index according to its position.
	 * If it's not a function argument (local variable) its index increased by 1.
	 * @param currIndex current index
	 * @return new index
	 */
	private int newIndex( int currIndex){
		return currIndex + 1 < this.argumentsSize ? currIndex : currIndex + 1;
	}
	
	private String getArrayMemberType( String arrayType){
		if( arrayType.charAt(arrayType.length() - 1) == ';' && // primitive array 
				arrayType.charAt(1) != '[' ) // array of arrays  
			return arrayType.substring(2, arrayType.length()-1);

		return arrayType.substring(1, arrayType.length()); // array of Objects
	}
}
