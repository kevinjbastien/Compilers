package visitor;

import syntaxtree.*;

import errorMsg.*;
import java.io.*;

public class CG3Visitor extends ASTvisitor {


	// the purpose here is to annotate things with their offsets:
	// - formal parameters, with respect to the (callee) frame
	// - local variables, with respect to the frame
	// - instance variables, with respect to their slot in the object
	// - methods, with respect to their slot in the v-table
	// - while statements, with respect to the stack-size at the time
	//   of loop-exit
	
	// IO stream to which we will emit code
	CodeStream code;

	// current stack height
	int stackHeight;
	
	// error-message object
	ErrorMsg errorMsg;
	
	public CG3Visitor(ErrorMsg e, PrintStream out) {
		initInstanceVars(e, out);
	}
	
	private void initInstanceVars(ErrorMsg e, PrintStream out) {
		errorMsg = e;
		code = new CodeStream(out, errorMsg);
		stackHeight = 0;
	}
	
	public Object visitLocalVarDecl(LocalVarDecl n){
		n.initExp.accept(this);
		n.offset = -stackHeight;
		return null;
	}
	
	public Object visitIntegerLiteral(IntegerLiteral n){
		code.emit(n, "subu $sp,$sp,8");
		stackHeight = stackHeight + 8;
		code.emit(n, "sw $s5,4($sp)");
		code.emit(n, "li $t0," + n.val);
		code.emit(n, "sw $t0,($sp)");
		return null;
	}
	
	public Object visitStringLiteral(StringLiteral n){
		code.emit(n, "subu $sp,$sp,4");
		stackHeight = stackHeight + 4;
		code.emit(n, "la $t0," + n.uniqueCgRep.uniqueId);
		code.emit(n, "sw $t0,($sp)");
		return null;
	}
	
	public Object visitPlus(Plus n){
		n.left.accept(this);
		n.right.accept(this);
		
		code.emit(n, "lw $t0,($sp)");
		code.emit(n, "lw $t1,8($sp)");
		code.emit(n, "addu $t0,$t0,$t1");
		code.emit(n, "addu $sp,$sp,8");
		stackHeight = stackHeight - 8;
		code.emit(n, "sw $t0,($sp)");
		return null;
	}
	
	public Object visitMinus(Minus n){
		n.left.accept(this);
		n.right.accept(this);
		
		code.emit(n, "lw $t0,($sp)");
		code.emit(n, "lw $t1,8($sp)");
		code.emit(n, "subu $t0,$t0,$t1");
		code.emit(n, "addu $sp,$sp,8");
		stackHeight = stackHeight - 8;
		code.emit(n, "sw $t0,($sp)");
		return null;
	}
	
	public Object visitThis(This n){
		code.emit(n, "subu $sp,$sp,4");
		stackHeight = stackHeight + 4;
		code.emit(n, "sw $s2,($sp)");
		return null;
	}
	
	public Object visitSuper(Super n){
		code.emit(n, "subu $sp,$sp,4");
		stackHeight = stackHeight + 4;
		code.emit(n, "sw $s2,($sp)");
		return null;
	}
	
	public Object visitNewObject(NewObject n){
		code.emit(n, "subu $sp,$sp,4");
		code.emit(n, "sw $zero,($sp)");
		stackHeight = stackHeight + 4;
		return null;
	}
	
	public Object visitCall(Call n){
		int saveStackHeight = stackHeight;
		n.obj.accept(this);
		n.parms.accept(this);
		if(n.methodLink.pos < 0){
			code.emit(n, "jal " + n.methodLink.name);
		}
		else{
			code.emit(n, "jal fcn_" + n.methodLink.uniqueId + "_" + n.methodLink.name);
		}
		
		// set stackHeight to saved one plus 0, 4, or 8, depending on
		// the expression�s type
		int set;
		if (n.type instanceof VoidType) {
			set = 0;
		}
		else if(n.type instanceof IntegerType) {
			set = 8;
		}
		else {
			set = 4;
		}
		stackHeight = saveStackHeight + set;
		return null;
	}
	
	public Object visitIdentifierExp(IdentifierExp n){
		if(n.link instanceof InstVarDecl){
			code.emit(n, "lw $t0," + n.link.offset + "($s2)");
		}
		else {
			int stackDepth = stackHeight + n.link.offset;
			code.emit(n, "lw $t0," + stackDepth + "($sp)");
		}
		if(n.type instanceof IntegerType){
			code.emit(n, "subu $sp,$sp,8");
			stackHeight = stackHeight + 8;
			code.emit(n, "sw $s5,4($sp)");
			code.emit(n, "sw $t0,($sp)");
		}
		else {
			code.emit(n, "subu $sp,$sp,4");
			stackHeight = stackHeight + 4;
			code.emit(n, "sw $t0,($sp)");
		}
		return null;
	}
	
	public Object visitProgram(Program n){
		code.emit(n, ".text");
		code.emit(n, ".globl main");
		code.emit(n, "main:");
		code.emit(n, "# initialize registers, etc.");
		code.emit(n, "jal vm_init");
		stackHeight = 0;
		n.mainStatement.accept(this);
		code.emit(n, "# exit program");
		code.emit(n, "li $v0,10");
		code.emit(n, "syscall");
		n.classDecls.accept(this);
		
		//emit dummy labels for part A
		code.emit(n, "CLASS_String:");
		code.emit(n, "CLASS_Object:");
		code.flush();
		return null;
	}
	
	public Object visitMethodDeclVoid(MethodDeclVoid n){
		code.emit(n, ".globl fcn_" + n.uniqueId + "_" + n.name);
		code.emit(n, "fcn_" + n.uniqueId + "_" + n.name + ":");
		code.emit(n, "subu $sp,$sp,8");
		code.emit(n, "sw $ra,4($sp)");
		code.emit(n, "sw $s2,($sp)");
		stackHeight = 4;
		
		//determine stack-top-relative location of method�s
		//this-pointer: 4 + object�s thisPointerOffset
		int location = 4 + n.thisPtrOffset;
		code.emit(n, "lw $s2," + location + "($sp)");
		
		n.stmts.accept(this);
		
		//determine offset of saved return address and
		//saved this-pointer relative to current stack height
		int offsetSavedReturnAddress = stackHeight;
		code.emit(n, "lw $ra," + offsetSavedReturnAddress + "($sp)");
		
		int savedThisPtr = stackHeight - 4;
		code.emit(n, "lw $s2, " + savedThisPtr + "($sp)");
		
		int amount = stackHeight + 8;
		code.emit(n, "addu $sp,$sp," + amount);
		code.emit(n, "jr $ra");
		return null;
	}
}


	
