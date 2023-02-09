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
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorTransformer extends JCTree.Visitor {
    private static class Shared {
        public TreeMaker treeMaker;
        public Names names;

        public JCTree.JCExpression stepType;

        private int nextId;
        private int nextIteratorTmpId;

        public ListBuffer<GeneratorBranch> branches;
        private Map<Name, JCTree.JCExpression> variableMap;

        public Shared(Context cx, JCTree.JCExpression stepType) {
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

    private ListBuffer<JCTree.JCStatement> current;
    private Map<Name, JCTree.JCExpression> scopeVariableMap;

    private GeneratorTransformer(Shared shared, ListBuffer<JCTree.JCStatement> current) {
        this.shared = shared;

        this.current = current;
        scopeVariableMap = new HashMap<>();
    }

    public static GeneratorTransformer createRoot(Context cx, JCTree.JCExpression stepType) {
        Shared shared = new Shared(cx, stepType);
        return new GeneratorTransformer(shared, shared.createBranch().statements);
    }

    public GeneratorMap finish() {
        finishSub();
        current.add(shared.treeMaker
                .Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(shared.names.fromString(Constants.RESULT_VAR_NAME)),
                        shared.treeMaker.Literal(TypeTag.BOT, null))));
        current.add(shared.treeMaker
                .Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(shared.names.fromString(Constants.STEP_VAR_NAME)),
                        shared.treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH))));

        return new GeneratorMap(shared.variableMap, shared.branches);
    }

    private void finishSub() {
        for (Entry<Name, JCTree.JCExpression> entry : scopeVariableMap.entrySet()) {
            if (entry.getValue() instanceof JCTree.JCPrimitiveTypeTree) {
                continue;
            }

            current.add(shared.treeMaker.Exec(shared.treeMaker.Assign(
                    shared.treeMaker.Ident(entry.getKey()),
                    shared.treeMaker.Literal(TypeTag.BOT, null))));
        }

        shared.variableMap.putAll(scopeVariableMap);
    }

    private void callBranch(ListBuffer<JCTree.JCStatement> list, int id) {
        list.add(shared.treeMaker.Exec(shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Ident(shared.names.fromString(Constants.BRANCH_METHOD_PREFIX + id)), List.nil())));
    }

    private int nextBranch() {
        GeneratorBranch next = shared.createBranch();
        current = next.statements;
        return next.id;
    }

    private JCTree.JCExpressionStatement createAssignResult(JCTree.JCExpression expr) {
        return shared.treeMaker
                .Exec(shared.treeMaker.Assign(
                        shared.treeMaker.Ident(shared.names.fromString(Constants.RESULT_VAR_NAME)), expr));
    }

    private void stepBranch(JCTree.JCExpression stepExpr) {
        current.add(createAssignResult(stepExpr));
        shared.treeMaker
                .Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(shared.names.fromString(Constants.STEP_VAR_NAME)),
                        shared.treeMaker.Literal(TypeTag.INT, shared.getNextId())))
                .accept(this);
        shared.treeMaker.Return(null).accept(this);

        nextBranch();
    }

    private void stepAllBranch(JCTree.JCExpression stepAllExpr) {
        Name iterName = shared.nextTmpIteratorName();

        JCTree.JCMethodInvocation hasNextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("hasNext")),
                List.nil());

        JCTree.JCMethodInvocation nextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("next")),
                List.nil());

        current = withScope(current, (sub) -> {
            shared.treeMaker.VarDef(shared.treeMaker.Modifiers(0), iterName, shared.treeMaker.TypeApply(
                    TreeMakerUtil.createClassName(shared.treeMaker, shared.names, "java", "util", "Iterator"),
                    List.of(shared.stepType)),
                    stepAllExpr).accept(sub);

            shared.treeMaker
                    .Exec(shared.treeMaker.Assign(
                            shared.treeMaker.Ident(shared.names.fromString(Constants.STEP_VAR_NAME)),
                            shared.treeMaker.Literal(TypeTag.INT, shared.getNextId())))
                    .accept(sub);

            callBranch(current, sub.nextBranch());

            sub.current.add(shared.treeMaker.If(hasNextInv, shared.treeMaker.Block(0, List.of(
                    createAssignResult(nextInv),
                    shared.treeMaker.Return(null))), null));
        });
    }

    private ListBuffer<JCTree.JCStatement> withScope(ListBuffer<JCTree.JCStatement> subCurrent,
            Consumer<GeneratorTransformer> consumer) {
        GeneratorTransformer sub = new GeneratorTransformer(shared, subCurrent);
        consumer.accept(sub);

        sub.finishSub();

        return sub.current;
    }

    @Override
    public void visitIf(JCTree.JCIf that) {
        ListBuffer<JCTree.JCStatement> workCurrent = current;

        ListBuffer<JCTree.JCStatement> thenBuf = new ListBuffer<>();
        ListBuffer<JCTree.JCStatement> elseBuf = new ListBuffer<>();

        ListBuffer<JCTree.JCStatement> thenLastBuf = withScope(thenBuf, that.thenpart::accept);

        ListBuffer<JCTree.JCStatement> elseLastBuf = elseBuf;
        if (that.elsepart != null) {
            elseLastBuf = withScope(elseBuf, that.elsepart::accept);
        }

        boolean thenStepped = thenLastBuf != thenBuf;
        boolean elseStepped = elseLastBuf != elseBuf;

        JCTree.JCIf ifStatement = shared.treeMaker.If(
                that.cond,
                shared.treeMaker.Block(0, thenBuf.toList()),
                elseBuf.isEmpty() ? null : shared.treeMaker.Block(0, elseBuf.toList()));

        if (thenStepped || elseStepped) {
            int nextId = nextBranch();

            if (thenStepped) {
                callBranch(thenLastBuf, nextId);
            }

            if (elseStepped && elseLastBuf != null) {
                callBranch(elseLastBuf, nextId);
            }

            workCurrent.add(ifStatement);

            if (!thenStepped || !elseStepped) {
                callBranch(workCurrent, nextId);
            }
        } else {
            workCurrent.add(ifStatement);
        }
    }

    private void doConditionalLoop(JCTree.JCExpression cond, JCTree.JCStatement body) {
        ListBuffer<JCTree.JCStatement> workCurrent = current;

        ListBuffer<JCTree.JCStatement> bodyBuf = new ListBuffer<>();
        ListBuffer<JCTree.JCStatement> bodyEndBuf = withScope(bodyBuf, body::accept);

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
    public void visitForeachLoop(JCTree.JCEnhancedForLoop that) {
        Name iterName = shared.nextTmpIteratorName();

        JCTree.JCMethodInvocation iteratorInv = shared.treeMaker.Apply(
                List.nil(),
                shared.treeMaker.Select(that.expr, shared.names.fromString("iterator")),
                List.nil());

        JCTree.JCMethodInvocation hasNextInv = shared.treeMaker.Apply(List.nil(),
                shared.treeMaker.Select(shared.treeMaker.Ident(iterName), shared.names.fromString("hasNext")),
                List.nil());

        JCTree.JCMethodInvocation nextInv = shared.treeMaker.Apply(List.nil(),
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
    public void visitForLoop(JCTree.JCForLoop that) {
        current = withScope(current, (sub) -> {
            for (JCTree.JCStatement statement : that.init) {
                statement.accept(sub);
            }

            ListBuffer<JCTree.JCStatement> buf = new ListBuffer<>();
            buf.append(that.body);
            buf.addAll(that.step);

            sub.doConditionalLoop(that.cond, shared.treeMaker.Block(0, buf.toList()));
        });
    }

    @Override
    public void visitWhileLoop(JCTree.JCWhileLoop that) {
        if (that.body == null) {
            super.visitWhileLoop(that);
            return;
        }

        doConditionalLoop(that.cond, that.body);
    }

    @Override
    public void visitBlock(JCTree.JCBlock that) {
        current = withScope(current, (sub) -> {
            for (JCTree.JCStatement statement : that.stats) {
                statement.accept(sub);
            }
        });
    }

    @Override
    public void visitExec(JCTree.JCExpressionStatement that) {
        if (that.expr instanceof JCTree.JCMethodInvocation methodInv && methodInv.args.size() == 1) {
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
    public void visitVarDef(JCTree.JCVariableDecl that) {
        Name varName = that.name;
        if (that.init != null) {
            shared.treeMaker.Exec(shared.treeMaker.Assign(shared.treeMaker.Ident(varName), that.init)).accept(this);
        }

        scopeVariableMap.put(varName, that.vartype);
    }

    @Override
    public void visitTree(JCTree that) {
        if (that instanceof JCTree.JCStatement statement) {
            current.add(statement);
        }
    }
}
