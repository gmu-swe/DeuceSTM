package org.deuce.transform.asm;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

import org.deuce.objectweb.asm.ClassReader;
import org.deuce.objectweb.asm.ClassVisitor;
import org.deuce.objectweb.asm.ClassWriter;
import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.commons.JSRInlinerAdapter;
import org.deuce.objectweb.asm.util.TraceClassVisitor;


/**
 * Provides a wrapper over {@link ClassAdapter}
 * @author Guy Korland
 * @since 1.0
 */
public class ByteCodeVisitor extends ClassVisitor {

	@Override
	public MethodVisitor visitMethod(int access, String name, String desc,
			String signature, String[] exceptions) {
		MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
		return new JSRInlinerAdapter(mv, access, name, desc, signature, exceptions);
	}

	protected final String className;
	//The maximal bytecode version to transform.
	private int maximalversion = Integer.MAX_VALUE;

	public ByteCodeVisitor(String className) {

		super(Opcodes.ASM5, new ClassWriter(ClassWriter.COMPUTE_MAXS));
		this.className = className;
	}
	
	@Override
	public void visit(final int version, final int access, final String name,
			final String signature, final String superName, final String[] interfaces) {
		if(version > maximalversion) // version higher than allowed 
			throw VersionException.INSTANCE;
		super.visit(version, access, name, signature, superName, interfaces);
	}
	
	public byte[] visit( byte[] bytes){
		ClassReader cr = new ClassReader(bytes);
		PrintWriter pw = null;
//		try {
			pw = new PrintWriter(System.err,true);
//		} catch (FileNotFoundException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		TraceClassVisitor tcv = new TraceClassVisitor(this,pw);
		try{
		cr.accept(this, ClassReader.EXPAND_FRAMES);
		return ((ClassWriter)super.cv).toByteArray();
		}
		catch(Throwable t)
		{
//			tcv.visitEnd();
			throw new IllegalStateException(t);
		}
	}
	
	
	public String getClassName() {
		return className;
	}
	
	private static class VersionException extends RuntimeException{
		private static final long serialVersionUID = 1L;
		public static VersionException INSTANCE = new VersionException();
	}
	
}
