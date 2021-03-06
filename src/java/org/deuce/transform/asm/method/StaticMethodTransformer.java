package org.deuce.transform.asm.method;

import java.util.List;

import org.deuce.objectweb.asm.MethodVisitor;
import org.deuce.objectweb.asm.Opcodes;
import org.deuce.objectweb.asm.Type;
import org.deuce.transform.asm.Field;

import static org.deuce.objectweb.asm.Opcodes.*;

public class StaticMethodTransformer extends MethodVisitor {

	final static public String CLASS_BASE = "__CLASS_BASE__";
	
	private final List<Field> fields;
	private final String className;
	private final MethodVisitor staticMethod;
	private final String fieldsHolderName;
	private final String staticField;
	
	public StaticMethodTransformer(MethodVisitor mv, MethodVisitor staticMethod, List<Field> fields,
			String staticField, String className, String fieldsHolderName) {
		super(Opcodes.ASM5, mv);
		this.staticMethod = staticMethod;
		this.fields = fields;
		this.staticField = staticField;
		this.className = className;
		this.fieldsHolderName = fieldsHolderName;
	}

	@Override
	public void visitCode() {
		if(fields.size() > 0){
			for( Field field : fields)
				addField( field);
			
			if(staticField != null)
				addClassBase(staticField);
		}
	}

	private void addField( Field field) {
		staticMethod.visitLdcInsn(Type.getObjectType(className));
		staticMethod.visitLdcInsn(field.getFieldName());
		staticMethod.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
		"(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
		staticMethod.visitMethodInsn(INVOKESTATIC, "org/deuce/reflection/AddressUtil",
				"getAddress", "(Ljava/lang/reflect/Field;)J", false);
		staticMethod.visitFieldInsn(PUTSTATIC, fieldsHolderName, field.getFieldNameAddress(), "J");
	}

	private void addClassBase(String staticFieldBase) {
		staticMethod.visitLdcInsn(Type.getObjectType(className));
		staticMethod.visitLdcInsn(staticFieldBase);
		staticMethod.visitMethodInsn(INVOKESTATIC, "org/deuce/reflection/AddressUtil",
				"staticFieldBase", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false);
		staticMethod.visitFieldInsn(PUTSTATIC, fieldsHolderName, CLASS_BASE, "Ljava/lang/Object;");
	}

	@Override
	public void visitEnd(){
		super.visitEnd();
		// TODO can we do it cleaner?
		if( super.mv != staticMethod)
			staticMethod.visitEnd();
	}
}
