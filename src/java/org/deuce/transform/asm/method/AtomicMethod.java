package org.deuce.transform.asm.method;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.deuce.Atomic;
import org.deuce.objectweb.asm.AnnotationVisitor;
import org.deuce.objectweb.asm.Label;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.objectweb.asm.commons.AnalyzerAdapter;
import org.deuce.objectweb.asm.commons.Method;
import org.deuce.objectweb.asm.tree.FrameNode;
import org.deuce.transaction.AbortTransactionException;
import org.deuce.transaction.Context;
import org.deuce.transaction.ContextDelegator;
import org.deuce.transaction.TransactionException;
import org.deuce.transform.asm.type.TypeCodeResolver;
import org.deuce.transform.asm.type.TypeCodeResolverFactory;
import static org.deuce.objectweb.asm.Opcodes.*;

/**
 * Used to replaced the original @atomic method with a method that run the transaction loop.
 * On each round the transaction contest reinitialized and the duplicated method is called with the 
 * transaction context.
 *  
 * @author Guy Korland
 */
public class AtomicMethod extends MethodVisitor {

	final static public String ATOMIC_DESCRIPTOR = Type.getDescriptor(Atomic.class);
	final static private AtomicInteger ATOMIC_BLOCK_COUNTER = new AtomicInteger(0);
	
	private Integer retries = Integer.getInteger("org.deuce.transaction.retries", Integer.MAX_VALUE);
	private String metainf = "";//Integer.getInteger("org.deuce.transaction.retries", Integer.MAX_VALUE);
	
	final private String className;
	final private String methodName;
	final private TypeCodeResolver returnReolver;
	final private TypeCodeResolver[] argumentReolvers;
	final private boolean isStatic;
	final private int variablesSize;
	final private Method newMethod;
	private boolean addFrames;
	private String methodDescriptor;
	
	public AtomicMethod(MethodVisitor mv, String className, String methodName,
			String descriptor, Method newMethod, boolean isStatic, boolean addFrames) {
		super(Opcodes.ASM5, mv);
		this.className = className;
		this.methodName = methodName;
		this.newMethod = newMethod;
		this.isStatic = isStatic;
		this.addFrames = addFrames;
		this.methodDescriptor = descriptor;
		
		Type returnType = Type.getReturnType(descriptor);
		Type[] argumentTypes = Type.getArgumentTypes(descriptor);

		returnReolver = TypeCodeResolverFactory.getReolver(returnType);
		argumentReolvers = new TypeCodeResolver[ argumentTypes.length];
		for( int i=0; i< argumentTypes.length ; ++i) {
			argumentReolvers[ i] = TypeCodeResolverFactory.getReolver( argumentTypes[ i]);
		}
		variablesSize = variablesSize( argumentReolvers, isStatic);
	}
	
	public void setAddFrames(boolean addFrames) {
		this.addFrames = addFrames;
	}

	@Override
	public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
		final AnnotationVisitor visitAnnotation = super.visitAnnotation(desc, visible);
		if( AtomicMethod.ATOMIC_DESCRIPTOR.equals(desc)){
			return new AnnotationVisitor(Opcodes.ASM5){
				
				public void visit(String name, Object value) {
					if( name.equals("retries"))
						AtomicMethod.this.retries = (Integer)value;
					
					if( name.equals("metainf"))
						AtomicMethod.this.metainf = (String)value;
					
					visitAnnotation.visit(name, value);
				}
				public AnnotationVisitor visitAnnotation(String name, String desc) {
					return visitAnnotation.visitAnnotation(name, desc);
				}
				public AnnotationVisitor visitArray(String name) {
					return visitAnnotation.visitArray(name);
				}
				public void visitEnd() {
					visitAnnotation.visitEnd();				
				}
				public void visitEnum(String name, String desc, String value) {
					visitAnnotation.visitEnum(name, desc, value);
				}
			};
		}
		return visitAnnotation;
	}

	/**
	public static boolean foo(Object s) throws IOException{

		Throwable throwable = null;
		Context context = ContextDelegator.getInstance();
		boolean commit = true;
		boolean result = true;
		for( int i=10 ; i>0 ; --i)
		{
			context.init(atomicBlockId, metainf);
			try
			{
				result = foo(s,context);
			}
			catch( AbortTransactionException ex)
			{
				context.rollback(); 
				throw ex;
			}
			catch( TransactionException ex)
			{
				commit = false;
			}
			catch( Throwable ex)
			{
				throwable = ex;
			}

			if( commit )
			{
				if( context.commit()){
					if( throwable != null)
						throw (IOException)throwable;
					return result;
				}
			}
			else
			{
				context.rollback(); 
				commit = true;
			}
		}
		throw new TransactionException();

	}
	 */
	public FrameNode getCurrentFrameNode(AnalyzerAdapter analyzerAdapter)
	{
		Object[] locals = removeLongsDoubleTopVal(analyzerAdapter.locals);
		Object[] stack = removeLongsDoubleTopVal(analyzerAdapter.stack);
		FrameNode ret = new FrameNode(Opcodes.F_FULL, locals.length, locals, stack.length, stack);
		ret.type = Opcodes.F_NEW;
		return ret;
	}
	public static Object[] removeLongsDoubleTopVal(List<Object> in) {
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
		return ret.toArray();
	}
	@Override
	public void visitCode() {

		final int indexIndex = variablesSize; // i
		final int contextIndex = indexIndex + 1; // context
		final int throwableIndex = contextIndex + 1;
		final int commitIndex = throwableIndex + 1;
		final int exceptionIndex = commitIndex + 1;
		final int resultIndex = exceptionIndex + 1;

		AnalyzerAdapter an = new AnalyzerAdapter(className, isStatic ? ACC_STATIC: ACC_PUBLIC, methodName, methodDescriptor, mv);
		Label l0 = new Label();
		Label l1 = new Label();
		Label l25 = new Label();
		an.visitTryCatchBlock(l0, l1, l25, AbortTransactionException.ABORT_TRANSACTION_EXCEPTION_INTERNAL);  // try{
		Label l2 = new Label();
		an.visitTryCatchBlock(l0, l1, l2, TransactionException.TRANSACTION_EXCEPTION_INTERNAL);  // try{ 
		Label l3 = new Label();
		an.visitTryCatchBlock(l0, l1, l3, Type.getInternalName( Throwable.class));  // try{
		
		Label l4 = new Label(); // Throwable throwable = null;
		an.visitLabel(l4);
		an.visitInsn(ACONST_NULL);
		an.visitVarInsn(ASTORE, throwableIndex);
		
		Label l5 = getContext(contextIndex,an); // Context context = ContextDelegator.getInstance();
			
		Label l6 = new Label(); // boolean commit = true;
		an.visitLabel(l6);
		an.visitInsn(ICONST_1);
		an.visitVarInsn(ISTORE, commitIndex);
		
		Label l7 = new Label(); // ... result = null;
		an.visitLabel(l7);
		if( returnReolver != null)
		{
			an.visitInsn( returnReolver.nullValueCode());
			an.visitVarInsn( returnReolver.storeCode(), resultIndex);
		}
		
		Label l8 = new Label(); // for( int i=10 ; ... ; ...)
		an.visitLabel(l8);
		an.visitLdcInsn( retries);
		an.visitVarInsn(ISTORE, indexIndex);
		FrameNode withNoStack = getCurrentFrameNode(an);
		Object[] locals = withNoStack.local.toArray();
		Label l9 = new Label();
		an.visitLabel(l9);
		Label l10 = new Label();
		an.visitJumpInsn(GOTO, l10);
		
		Label l11 = new Label(); // context.init(atomicBlockId, metainf);
		an.visitLabel(l11);
		if(addFrames)
			withNoStack.accept(an);
		an.visitVarInsn(ALOAD, contextIndex);
		an.visitLdcInsn(ATOMIC_BLOCK_COUNTER.getAndIncrement());
		an.visitLdcInsn(metainf);
		an.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "init", "(ILjava/lang/String;)V", true);

		/* result = foo( context, ...)  */ 
		an.visitLabel(l0);
		if( !isStatic) // load this id if not static
			an.visitVarInsn(ALOAD, 0);

		// load the rest of the arguments
		int local = isStatic ? 0 : 1;
		for( int i=0 ; i < argumentReolvers.length ; ++i) { 
			an.visitVarInsn(argumentReolvers[i].loadCode(), local);
			local += argumentReolvers[i].localSize(); // move to the next argument
		}
		
		an.visitVarInsn(ALOAD, contextIndex); // load the context
		
		if( isStatic)
			an.visitMethodInsn(INVOKESTATIC, className, methodName, newMethod.getDescriptor(), false); // ... = foo( ...
		else
			an.visitMethodInsn(INVOKEVIRTUAL, className, methodName, newMethod.getDescriptor(), false); // ... = foo( ...

		if( returnReolver != null) 
			an.visitVarInsn(returnReolver.storeCode(), resultIndex); // result = ...
		
		an.visitLabel(l1);
		Label l12 = new Label();
		FrameNode preL12 = getCurrentFrameNode(an);
		an.visitJumpInsn(GOTO, l12);

		/*catch( AbortTransactionException ex)
		{
			throw ex;
		}*/
		an.visitLabel(l25);
		if(addFrames)
			an.visitFrame(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"org/deuce/transaction/AbortTransactionException"});

		an.visitVarInsn(ASTORE, exceptionIndex);
		Label l27 = new Label();
		an.visitVarInsn(ALOAD, contextIndex);
		an.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "rollback", "()V", true);
		an.visitLabel(l27);
		an.visitVarInsn(ALOAD, exceptionIndex);
		an.visitInsn(ATHROW);
		Label l28 = new Label();
		an.visitLabel(l28);
		an.visitJumpInsn(GOTO, l12);
		
		/*catch( TransactionException ex)
		{
			commit = false;
		}*/
		an.visitLabel(l2);
		if(addFrames)
			an.visitFrame(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"org/deuce/transaction/TransactionException"});

		an.visitVarInsn(ASTORE, exceptionIndex);
		Label l13 = new Label();
		an.visitLabel(l13);
		an.visitInsn(ICONST_0);
		an.visitVarInsn(ISTORE, commitIndex);
		Label l14 = new Label();
		an.visitLabel(l14);
		an.visitJumpInsn(GOTO, l12);
		
		/*catch( Throwable ex)
		{
			throwable = ex;
		}*/
		an.visitLabel(l3);
		if(addFrames)
			mv.visitFrame(Opcodes.F_NEW, locals.length, locals, 1, new Object[] {"java/lang/Throwable"});

		an.visitVarInsn(ASTORE, exceptionIndex);
		Label l15 = new Label();
		an.visitLabel(l15);
		an.visitVarInsn(ALOAD, exceptionIndex);
		an.visitVarInsn(ASTORE, throwableIndex);
		
		/*
		 * if( commit )
			{
				if( context.commit()){
					if( throwable != null)
						throw (IOException)throwable;
					return result;
				}
			}
			else
			{
				context.rollback(); 
				commit = true;
			}
		 */
		an.visitLabel(l12); // if( commit )
		if(addFrames)
			preL12.accept(an);
		an.visitVarInsn(ILOAD, commitIndex);
		Label l16 = new Label();
		an.visitJumpInsn(IFEQ, l16);
		
		Label l17 = new Label(); // if( context.commit())
		an.visitLabel(l17);
		an.visitVarInsn(ALOAD, contextIndex);
		an.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "commit", "()Z", true);
		Label l18 = new Label();
		an.visitJumpInsn(IFEQ, l18);
		
		//		if( throwable != null)
		//			throw throwable;
		Label l19 = new Label();
		an.visitLabel(l19);
		an.visitVarInsn(ALOAD, throwableIndex);
		Label l20 = new Label();
		an.visitJumpInsn(IFNULL, l20);
		Label l21 = new Label();
		an.visitLabel(l21);
		an.visitVarInsn(ALOAD, throwableIndex);
		an.visitInsn(ATHROW);
		
		// return
		an.visitLabel(l20);
		if(addFrames)
			preL12.accept(an);

		if( returnReolver == null) {
			an.visitInsn( RETURN); // return;
		}
		else {
			an.visitVarInsn(returnReolver.loadCode(), resultIndex); // return result;
			an.visitInsn(returnReolver.returnCode());
		}
		
		an.visitJumpInsn(GOTO, l18);
		
		// else
		an.visitLabel(l16); // context.rollback(); 
		if(addFrames)
			preL12.accept(an);

		an.visitVarInsn(ALOAD, contextIndex);
		an.visitMethodInsn(INVOKEINTERFACE, Context.CONTEXT_INTERNAL, "rollback", "()V", true);
		
		an.visitInsn(ICONST_1); // commit = true;
		an.visitVarInsn(ISTORE, commitIndex);
		
		an.visitLabel(l18);  // for( ... ; i>0 ; --i) 
		if(addFrames)
			preL12.accept(an);

		an.visitIincInsn(indexIndex, -1);
		an.visitLabel(l10);
		if(addFrames)
			preL12.accept(an);

		an.visitVarInsn(ILOAD, indexIndex);
		an.visitJumpInsn(IFGT, l11);
		
		// throw new TransactionException("Failed to commit ...");
		Label l23 = throwTransactionException();
		
		/* locals */
		Label l24 = new Label();
		an.visitLabel(l24);
		an.visitLocalVariable("throwable", "Ljava/lang/Throwable;", null, l5, l24, throwableIndex);
		an.visitLocalVariable("context", Context.CONTEXT_DESC, null, l6, l24, contextIndex);
		an.visitLocalVariable("commit", "Z", null, l7, l24, commitIndex);
		if( returnReolver != null)
			an.visitLocalVariable("result", returnReolver.toString(), null, l8, l24, resultIndex);
		an.visitLocalVariable("i", "I", null, l9, l23, indexIndex);
		an.visitLocalVariable("ex", "Lorg/deuce/transaction/AbortTransactionException;", null, l27, l28, exceptionIndex);
		an.visitLocalVariable("ex", "Lorg/deuce/transaction/TransactionException;", null, l13, l14, exceptionIndex);
		an.visitLocalVariable("ex", "Ljava/lang/Throwable;", null, l15, l12, exceptionIndex);
		
		an.visitMaxs(6 + variablesSize, resultIndex + 2);
		an.visitEnd();
	}

	private Label getContext(final int contextIndex, AnalyzerAdapter an) {
		Label label = new Label();
		an.visitLabel(label); // Context context = ContextDelegator.getInstance();
		an.visitMethodInsn(INVOKESTATIC, ContextDelegator.CONTEXT_DELEGATOR_INTERNAL, "getInstance", "()Lorg/deuce/transaction/Context;", false);
		an.visitVarInsn(ASTORE, contextIndex);
		return label;
	}

	private Label throwTransactionException() {
		Label label = new Label();
		mv.visitLabel(label);
		mv.visitTypeInsn(NEW, "org/deuce/transaction/TransactionException");
		mv.visitInsn(DUP);
		mv.visitLdcInsn("Failed to commit the transaction in the defined retries.");
		mv.visitMethodInsn(INVOKESPECIAL, "org/deuce/transaction/TransactionException", "<init>", "(Ljava/lang/String;)V", false);
		mv.visitInsn(ATHROW);
		return label;
	}

	@Override
	public void visitFrame(int type, int local, Object[] local2, int stack, Object[] stack2) {
	}

	@Override
	public void visitIincInsn(int var, int increment) {
	}

	@Override
	public void visitInsn(int opcode) {
	}

	@Override
	public void visitIntInsn(int opcode, int operand) {
	}

	@Override
	public void visitJumpInsn(int opcode, Label label) {
	}

	@Override
	public void visitLabel(Label label) {
	}

	@Override
	public void visitEnd() {
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
	}


	@Override
	public void visitLdcInsn(Object cst) {
	}

	@Override
	public void visitLineNumber(int line, Label start) {
	}

	@Override
	public void visitLocalVariable(String name, String desc, String signature, Label start,
			Label end, int index) {
	}

	@Override
	public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
	}

	@Override
	public void visitMaxs(int maxStack, int maxLocals) {
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name, String desc) {
	}

	@Override
	public void visitMultiANewArrayInsn(String desc, int dims) {
	}

	@Override
	public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels) {
	}

	@Override
	public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
	}

	@Override
	public void visitTypeInsn(int opcode, String type) {
	}

	@Override
	public void visitVarInsn(int opcode, int var) {
	}

	public void setRetries(int retries) {
		this.retries = retries;
	}

	private int variablesSize( TypeCodeResolver[] types, boolean isStatic) {
		int i = isStatic ? 0 : 1;
		for( TypeCodeResolver type : types) {
			i += type.localSize();
		}
		return i;
	}
}
