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
        ListBuffer<JCStatement> bodyBuf = new ListBuffer<>();
        ListBuffer<JCStatement> bodyEndBuf = withScope(bodyBuf, body::accept);

        if (bodyBuf != bodyEndBuf) {
            callBranch(current, shared.getNextId());
            callBranch(bodyEndBuf, nextBranch());
        }

        current.add(shared.treeMaker.WhileLoop(cond, shared.treeMaker.Block(0, bodyBuf.toList())));
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
            if (that.body != null) {
                buf.append(that.body);
            }

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
    public void visitBindingPattern(JCBindingPattern that) {
        // TODO Auto-generated method stub
        super.visitBindingPattern(that);
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
    public void visitContinue(JCContinue that) {
        // TODO Auto-generated method stub
        super.visitContinue(that);
    }

    @Override
    public void visitDoLoop(JCDoWhileLoop that) {
        ListBuffer<JCStatement> bodyBuf = new ListBuffer<>();
        ListBuffer<JCStatement> bodyEndBuf = withScope(bodyBuf, that.body::accept);

        if (bodyBuf != bodyEndBuf) {
            callBranch(current, shared.getNextId());
            callBranch(bodyEndBuf, nextBranch());
        }

        current.add(shared.treeMaker.DoLoop(shared.treeMaker.Block(0, bodyBuf.toList()), that.cond));
    }

    @Override
    public void visitParenthesizedPattern(JCParenthesizedPattern that) {
        // TODO Auto-generated method stub
        super.visitParenthesizedPattern(that);
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
    public void visitTry(JCTry that) {
        // TODO Auto-generated method stub
        super.visitTry(that);
    }
}
