/*
 * Created on Wed Feb 08 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorTransformer extends Visitor {
    private static class Shared {
        public TreeMaker treeMaker;
        public Names names;

        public JCExpression stepType;

        private int nextId;
        private int nextIteratorTmpId;

        public ListBuffer<GeneratorBranch> branches;
        private Map<Name, JCExpression> variableMap;

        public Shared(Context cx, JCExpression stepType) {
            treeMaker = TreeMaker.instance(cx);
            names = Names.instance(cx);

            this.stepType = stepType;

            nextId = Constants.GENERATOR_STEP_START;
            nextIteratorTmpId = 0;

            branches = new ListBuffer<>();
            variableMap = new HashMap<>();
        }

        public int getNextId() {
            return nextId;
        }

        public Name nextTmpIteratorName() {
            return names.fromString(Constants.ITERATOR_TMP_PREFIX + (nextIteratorTmpId++));
        }

        public GeneratorBranch createBranch() {
            GeneratorBranch branch = new GeneratorBranch(nextId++, new ListBuffer<>());
            branches.add(branch);
            return branch;
        }
    }

    private Shared shared;

    private ListBuffer<JCStatement> current;
    private Map<Name, JCExpression> scopeVariableMap;

    private GeneratorTransformer(Shared shared, ListBuffer<JCStatement> current) {
        this.shared = shared;

        this.current = current;
        scopeVariableMap = new HashMap<>();
    }

    public static GeneratorTransformer createRoot(Context cx, JCExpression stepType) {
        Shared shared = new Shared(cx, stepType);
        return new GeneratorTransformer(shared, shared.createBranch().statements);
    }

    public GeneratorMap finish() {
        finishSub();
        current.add(shared.treeMaker
                .Exec(shared.treeMaker.Assign(
                        shared.treeMaker.Ident(shared.names.fromString(Constants.RESULT_VAR_NAME)),
                        shared.treeMaker.Literal(TypeTag.BOT, null))));
        current.add(shared.treeMaker
                .Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(shared.names.fromString(Constants.STEP_VAR_NAME)),
                        shared.treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH))));

        return new GeneratorMap(shared.variableMap, shared.branches);
    }

    private void finishSub() {
        for (Entry<Name, JCExpression> entry : scopeVariableMap.entrySet()) {
            if (entry.getValue() instanceof JCPrimitiveTypeTree) {
                continue;
            }

            current.add(shared.treeMaker.Exec(shared.treeMaker.Assign(
                    shared.treeMaker.Ident(entry.getKey()),
                    shared.treeMaker.Literal(TypeTag.BOT, null))));
        }

        shared.variableMap.putAll(scopeVariableMap);
    }

    private void callBranch(ListBuffer<JCStatement> list, int id) {
        list.add(shared.treeMaker.Exec(shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Ident(shared.names.fromString(Constants.BRANCH_METHOD_PREFIX + id)), List.nil())));
    }

    private int nextBranch() {
        GeneratorBranch next = shared.createBranch();
        current = next.statements;
        return next.id;
    }

    private JCExpressionStatement createAssignResult(JCExpression expr) {
        return shared.treeMaker
                .Exec(shared.treeMaker.Assign(
                        shared.treeMaker.Ident(shared.names.fromString(Constants.RESULT_VAR_NAME)), expr));
    }

    private JCExpressionStatement createSetStep(int id) {
        return shared.treeMaker
                .Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(shared.names.fromString(Constants.STEP_VAR_NAME)),
                        shared.treeMaker.Literal(TypeTag.INT, id)));
    }

    private void stepBranch(JCExpression stepExpr) {
        current.add(createAssignResult(stepExpr));
        current.add(createSetStep(shared.getNextId()));
        current.add(shared.treeMaker.Return(null));

        nextBranch();
    }

    private void stepAllBranch(JCExpression stepAllExpr) {
        Name iterName = shared.nextTmpIteratorName();

        JCMethodInvocation hasNextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("hasNext")),
                List.nil());

        JCMethodInvocation nextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("next")),
                List.nil());

        current = withScope(current, (sub) -> {
            shared.treeMaker.VarDef(shared.treeMaker.Modifiers(0), iterName, shared.treeMaker.TypeApply(
                    TreeMakerUtil.createClassName(shared.treeMaker, shared.names, "java", "util", "Iterator"),
                    List.of(shared.stepType)),
                    stepAllExpr).accept(sub);

            sub.current.add(shared.treeMaker
                    .Exec(shared.treeMaker.Assign(
                            shared.treeMaker.Ident(shared.names.fromString(Constants.STEP_VAR_NAME)),
                            shared.treeMaker.Literal(TypeTag.INT, shared.getNextId()))));

            callBranch(current, sub.nextBranch());

            sub.current.add(shared.treeMaker.If(hasNextInv, shared.treeMaker.Block(0, List.of(
                    createAssignResult(nextInv),
                    shared.treeMaker.Return(null))), null));
        });
    }

    private ListBuffer<JCStatement> withScope(ListBuffer<JCStatement> subCurrent,
            Consumer<GeneratorTransformer> consumer) {
        GeneratorTransformer sub = new GeneratorTransformer(shared, subCurrent);
        consumer.accept(sub);

        sub.finishSub();

        return sub.current;
    }

    @Override
    public void visitIf(JCIf that) {
        ListBuffer<JCStatement> workCurrent = current;

        ListBuffer<JCStatement> thenBuf = new ListBuffer<>();
        ListBuffer<JCStatement> elseBuf = new ListBuffer<>();

        ListBuffer<JCStatement> thenLastBuf = withScope(thenBuf, that.thenpart::accept);

        ListBuffer<JCStatement> elseLastBuf = elseBuf;
        if (that.elsepart != null) {
            elseLastBuf = withScope(elseBuf, that.elsepart::accept);
        }

        boolean thenStepped = thenLastBuf != thenBuf;
        boolean elseStepped = elseLastBuf != elseBuf;

        JCIf ifStatement = shared.treeMaker.If(
                that.cond,
                shared.treeMaker.Block(0, thenBuf.toList()),
                elseBuf.isEmpty() ? null : shared.treeMaker.Block(0, elseBuf.toList()));

        if (thenStepped || elseStepped) {
            int nextId = nextBranch();

            callBranch(thenLastBuf, nextId);

            if (elseLastBuf != null) {
                callBranch(elseLastBuf, nextId);
            }
        }

        workCurrent.add(ifStatement);
    }

    private void doConditionalLoop(JCExpression cond, JCStatement body) {
        ListBuffer<JCStatement> workCurrent = current;

        ListBuffer<JCStatement> bodyBuf = new ListBuffer<>();
        ListBuffer<JCStatement> bodyEndBuf = withScope(bodyBuf, body::accept);

        if (bodyBuf != bodyEndBuf) {
            int nextId = nextBranch();

            callBranch(workCurrent, nextId);

            current.add(shared.treeMaker.WhileLoop(cond, shared.treeMaker.Block(0, bodyBuf.toList())));

            callBranch(bodyEndBuf, nextId);
        } else {
            current.add(shared.treeMaker.WhileLoop(cond, shared.treeMaker.Block(0, bodyBuf.toList())));
        }
    }

    @Override
    public void visitForeachLoop(JCEnhancedForLoop that) {
        Name iterName = shared.nextTmpIteratorName();

        JCMethodInvocation iteratorInv = shared.treeMaker.Apply(
                List.nil(),
                shared.treeMaker.Select(that.expr, shared.names.fromString("iterator")),
                List.nil());

        JCMethodInvocation hasNextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("hasNext")),
                List.nil());

        JCMethodInvocation nextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("next")),
                List.nil());

        current = withScope(current, (sub) -> {
            shared.treeMaker.VarDef(shared.treeMaker.Modifiers(0), iterName, shared.treeMaker.TypeApply(
                    TreeMakerUtil.createClassName(shared.treeMaker, shared.names, "java", "util", "Iterator"),
                    List.of(that.var.vartype)), iteratorInv).accept(sub);

            that.var.accept(sub);

            sub.doConditionalLoop(hasNextInv, shared.treeMaker.Block(0, List.of(
                    shared.treeMaker.Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(that.var.name), nextInv)),
                    that.body)));
        });
    }

    @Override
    public void visitForLoop(JCForLoop that) {
        current = withScope(current, (sub) -> {
            for (JCStatement statement : that.init) {
                statement.accept(sub);
            }

            ListBuffer<JCStatement> buf = new ListBuffer<>();
            buf.append(that.body);
            buf.addAll(that.step);

            sub.doConditionalLoop(that.cond, shared.treeMaker.Block(0, buf.toList()));
        });
    }

    @Override
    public void visitWhileLoop(JCWhileLoop that) {
        if (that.body == null) {
            super.visitWhileLoop(that);
            return;
        }

        doConditionalLoop(that.cond, that.body);
    }

    @Override
    public void visitBlock(JCBlock that) {
        current = withScope(current, (sub) -> {
            for (JCStatement statement : that.stats) {
                statement.accept(sub);
            }
        });
    }

    @Override
    public void visitExec(JCExpressionStatement that) {
        if (that.expr instanceof JCMethodInvocation methodInv && methodInv.args.size() == 1) {
            String method = methodInv.meth.toString();

            if ("step".equals(method)) {
                stepBranch(methodInv.args.head);
            } else if ("stepAll".equals(method)) {
                stepAllBranch(methodInv.args.head);
            } else {
                super.visitExec(that);
            }
            return;
        }

        super.visitExec(that);
    }

    @Override
    public void visitVarDef(JCVariableDecl that) {
        Name varName = that.name;
        if (that.init != null) {
            shared.treeMaker.Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(varName), that.init)).accept(this);
        }

        scopeVariableMap.put(varName, that.vartype);
    }

    @Override
    public void visitTree(JCTree that) {
        if (that instanceof JCStatement statement) {
            current.add(statement);
        }
    }

    @Override
    public void visitReturn(JCReturn that) {
        if (that.expr != null) {
            super.visitReturn(that);
            return;
        }

        current.add(createSetStep(Constants.GENERATOR_STEP_FINISH));
        current.add(shared.treeMaker.Return(null));
    }

    @Override
    public void visitAnnotatedType(JCAnnotatedType that) {
        // TODO Auto-generated method stub
        super.visitAnnotatedType(that);
    }

    @Override
    public void visitAnnotation(JCAnnotation that) {
        // TODO Auto-generated method stub
        super.visitAnnotation(that);
    }

    @Override
    public void visitApply(JCMethodInvocation that) {
        // TODO Auto-generated method stub
        super.visitApply(that);
    }

    @Override
    public void visitAssert(JCAssert that) {
        // TODO Auto-generated method stub
        super.visitAssert(that);
    }

    @Override
    public void visitAssign(JCAssign that) {
        // TODO Auto-generated method stub
        super.visitAssign(that);
    }

    @Override
    public void visitAssignop(JCAssignOp that) {
        // TODO Auto-generated method stub
        super.visitAssignop(that);
    }

    @Override
    public void visitBinary(JCBinary that) {
        // TODO Auto-generated method stub
        super.visitBinary(that);
    }

    @Override
    public void visitBindingPattern(JCBindingPattern that) {
        // TODO Auto-generated method stub
        super.visitBindingPattern(that);
    }

    @Override
    public void visitBreak(JCBreak that) {
        // TODO Auto-generated method stub
        super.visitBreak(that);
    }

    @Override
    public void visitCase(JCCase that) {
        // TODO Auto-generated method stub
        super.visitCase(that);
    }

    @Override
    public void visitCatch(JCCatch that) {
        // TODO Auto-generated method stub
        super.visitCatch(that);
    }

    @Override
    public void visitClassDef(JCClassDecl that) {
        // TODO Auto-generated method stub
        super.visitClassDef(that);
    }

    @Override
    public void visitConditional(JCConditional that) {
        // TODO Auto-generated method stub
        super.visitConditional(that);
    }

    @Override
    public void visitContinue(JCContinue that) {
        // TODO Auto-generated method stub
        super.visitContinue(that);
    }

    @Override
    public void visitDefaultCaseLabel(JCDefaultCaseLabel that) {
        // TODO Auto-generated method stub
        super.visitDefaultCaseLabel(that);
    }

    @Override
    public void visitDoLoop(JCDoWhileLoop that) {
        // TODO Auto-generated method stub
        super.visitDoLoop(that);
    }

    @Override
    public void visitErroneous(JCErroneous that) {
        // TODO Auto-generated method stub
        super.visitErroneous(that);
    }

    @Override
    public void visitExports(JCExports that) {
        // TODO Auto-generated method stub
        super.visitExports(that);
    }

    @Override
    public void visitGuardPattern(JCGuardPattern that) {
        // TODO Auto-generated method stub
        super.visitGuardPattern(that);
    }

    @Override
    public void visitIdent(JCIdent that) {
        // TODO Auto-generated method stub
        super.visitIdent(that);
    }

    @Override
    public void visitImport(JCImport that) {
        // TODO Auto-generated method stub
        super.visitImport(that);
    }

    @Override
    public void visitIndexed(JCArrayAccess that) {
        // TODO Auto-generated method stub
        super.visitIndexed(that);
    }

    @Override
    public void visitLabelled(JCLabeledStatement that) {
        // TODO Auto-generated method stub
        super.visitLabelled(that);
    }

    @Override
    public void visitLambda(JCLambda that) {
        // TODO Auto-generated method stub
        super.visitLambda(that);
    }

    @Override
    public void visitLetExpr(LetExpr that) {
        // TODO Auto-generated method stub
        super.visitLetExpr(that);
    }

    @Override
    public void visitLiteral(JCLiteral that) {
        // TODO Auto-generated method stub
        super.visitLiteral(that);
    }

    @Override
    public void visitMethodDef(JCMethodDecl that) {
        // TODO Auto-generated method stub
        super.visitMethodDef(that);
    }

    @Override
    public void visitModifiers(JCModifiers that) {
        // TODO Auto-generated method stub
        super.visitModifiers(that);
    }

    @Override
    public void visitModuleDef(JCModuleDecl that) {
        // TODO Auto-generated method stub
        super.visitModuleDef(that);
    }

    @Override
    public void visitNewArray(JCNewArray that) {
        // TODO Auto-generated method stub
        super.visitNewArray(that);
    }

    @Override
    public void visitNewClass(JCNewClass that) {
        // TODO Auto-generated method stub
        super.visitNewClass(that);
    }

    @Override
    public void visitOpens(JCOpens that) {
        // TODO Auto-generated method stub
        super.visitOpens(that);
    }

    @Override
    public void visitPackageDef(JCPackageDecl that) {
        // TODO Auto-generated method stub
        super.visitPackageDef(that);
    }

    @Override
    public void visitParens(JCParens that) {
        // TODO Auto-generated method stub
        super.visitParens(that);
    }

    @Override
    public void visitParenthesizedPattern(JCParenthesizedPattern that) {
        // TODO Auto-generated method stub
        super.visitParenthesizedPattern(that);
    }

    @Override
    public void visitProvides(JCProvides that) {
        // TODO Auto-generated method stub
        super.visitProvides(that);
    }

    @Override
    public void visitReference(JCMemberReference that) {
        // TODO Auto-generated method stub
        super.visitReference(that);
    }

    @Override
    public void visitRequires(JCRequires that) {
        // TODO Auto-generated method stub
        super.visitRequires(that);
    }

    @Override
    public void visitSelect(JCFieldAccess that) {
        // TODO Auto-generated method stub
        super.visitSelect(that);
    }

    @Override
    public void visitSkip(JCSkip that) {
        // TODO Auto-generated method stub
        super.visitSkip(that);
    }

    @Override
    public void visitSwitch(JCSwitch that) {
        // TODO Auto-generated method stub
        super.visitSwitch(that);
    }

    @Override
    public void visitSwitchExpression(JCSwitchExpression that) {
        // TODO Auto-generated method stub
        super.visitSwitchExpression(that);
    }

    @Override
    public void visitSynchronized(JCSynchronized that) {
        // TODO Auto-generated method stub
        super.visitSynchronized(that);
    }

    @Override
    public void visitThrow(JCThrow that) {
        // TODO Auto-generated method stub
        super.visitThrow(that);
    }

    @Override
    public void visitTopLevel(JCCompilationUnit that) {
        // TODO Auto-generated method stub
        super.visitTopLevel(that);
    }

    @Override
    public void visitTry(JCTry that) {
        // TODO Auto-generated method stub
        super.visitTry(that);
    }

    @Override
    public void visitTypeApply(JCTypeApply that) {
        // TODO Auto-generated method stub
        super.visitTypeApply(that);
    }

    @Override
    public void visitTypeArray(JCArrayTypeTree that) {
        // TODO Auto-generated method stub
        super.visitTypeArray(that);
    }

    @Override
    public void visitTypeBoundKind(TypeBoundKind that) {
        // TODO Auto-generated method stub
        super.visitTypeBoundKind(that);
    }

    @Override
    public void visitTypeCast(JCTypeCast that) {
        // TODO Auto-generated method stub
        super.visitTypeCast(that);
    }

    @Override
    public void visitTypeIdent(JCPrimitiveTypeTree that) {
        // TODO Auto-generated method stub
        super.visitTypeIdent(that);
    }

    @Override
    public void visitTypeIntersection(JCTypeIntersection that) {
        // TODO Auto-generated method stub
        super.visitTypeIntersection(that);
    }

    @Override
    public void visitTypeParameter(JCTypeParameter that) {
        // TODO Auto-generated method stub
        super.visitTypeParameter(that);
    }

    @Override
    public void visitTypeTest(JCInstanceOf that) {
        // TODO Auto-generated method stub
        super.visitTypeTest(that);
    }

    @Override
    public void visitTypeUnion(JCTypeUnion that) {
        // TODO Auto-generated method stub
        super.visitTypeUnion(that);
    }

    @Override
    public void visitUnary(JCUnary that) {
        // TODO Auto-generated method stub
        super.visitUnary(that);
    }

    @Override
    public void visitUses(JCUses that) {
        // TODO Auto-generated method stub
        super.visitUses(that);
    }

    @Override
    public void visitWildcard(JCWildcard that) {
        // TODO Auto-generated method stub
        super.visitWildcard(that);
    }

    @Override
    public void visitYield(JCYield that) {
        // TODO Auto-generated method stub
        super.visitYield(that);
    }
}
