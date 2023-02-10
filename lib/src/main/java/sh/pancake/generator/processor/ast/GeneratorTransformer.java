/*
 * Created on Wed Feb 08 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorTransformer extends Visitor {
    private final ClassMemberAlloc alloc;

    private final GeneratorClass genClass;

    private ListBuffer<JCStatement> current;
    private Map<Name, JCVariableDecl> scopeVariables;

    private GeneratorTransformer(ClassMemberAlloc alloc, GeneratorClass genClass, ListBuffer<JCStatement> current) {
        this.alloc = alloc;
        this.genClass = genClass;

        this.current = current;
        scopeVariables = new HashMap<>();
    }

    public static GeneratorTransformer createRoot(ClassMemberAlloc alloc, JCExpression resultType) {
        GeneratorClass genClass = new GeneratorClass(alloc, resultType);
        GeneratorBranch branch = alloc.createBranch();

        genClass.branches.add(branch);
        return new GeneratorTransformer(alloc, genClass, branch.statements);
    }

    public GeneratorClass finish() {
        finishSub();
        current.add(alloc.treeMaker
                .Exec(alloc.treeMaker.Assign(
                        alloc.treeMaker.Ident(genClass.resultField.name),
                        alloc.treeMaker.Literal(TypeTag.BOT, null))));
        current.add(alloc.treeMaker
                .Exec(alloc.treeMaker.Assign(alloc.treeMaker.Ident(genClass.stateField.name),
                        alloc.treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH))));

        return genClass;
    }

    private void finishSub() {
        for (JCVariableDecl variable : scopeVariables.values()) {
            if (variable.vartype instanceof JCPrimitiveTypeTree) {
                continue;
            }

            current.add(alloc.treeMaker.Exec(alloc.treeMaker.Assign(
                    alloc.treeMaker.Ident(variable.name),
                    alloc.treeMaker.Literal(TypeTag.BOT, null))));
        }

        genClass.fields.putAll(scopeVariables);
    }

    private JCVariableDecl createLocalField(JCExpression type, @Nullable JCExpression init) {
        JCVariableDecl decl = alloc.createPrivateField(type);
        scopeVariables.put(decl.name, decl);

        if (init != null) {
            current.add(
                    alloc.treeMaker.Exec(alloc.treeMaker.Assign(alloc.treeMaker.Ident(decl.name), init)));
        }

        return decl;
    }

    private JCExpressionStatement createCallBranchStatement(GeneratorBranch branch) {
        return alloc.treeMaker
                .Exec(alloc.treeMaker.Apply(List.nil(), alloc.treeMaker.Ident(branch.name), List.nil()));
    }

    private GeneratorBranch nextBranch() {
        GeneratorBranch next = alloc.createBranch();
        genClass.branches.add(next);
        current = next.statements;
        return next;
    }

    private JCExpressionStatement createAssignResult(JCExpression expr) {
        return alloc.treeMaker
                .Exec(alloc.treeMaker.Assign(
                        alloc.treeMaker.Ident(genClass.resultField.name), expr));
    }

    private JCExpressionStatement createAssignStep(int id) {
        return alloc.treeMaker
                .Exec(alloc.treeMaker.Assign(alloc.treeMaker.Ident(genClass.stateField.name),
                        alloc.treeMaker.Literal(TypeTag.INT, id)));
    }

    private void stepBranch(JCExpression stepExpr) {
        ListBuffer<JCStatement> buf = current;

        buf.add(createAssignResult(stepExpr));
        buf.add(createAssignStep(nextBranch().id));
        buf.add(alloc.treeMaker.Return(null));
    }

    private void stepAllBranch(JCExpression stepAllExpr) {
        current = withScope(current, (sub) -> {
            JCVariableDecl iterField = sub.createLocalField(alloc.treeMaker.TypeApply(
                    TreeMakerUtil.createClassName(alloc.treeMaker, alloc.names, "java", "util", "Iterator"),
                    List.of(genClass.resultField.vartype)), stepAllExpr);

            JCMethodInvocation hasNextInv = alloc.treeMaker.Apply(List.nil(),
                    alloc.treeMaker.Select(alloc.treeMaker.Ident(iterField.name), alloc.names.fromString("hasNext")),
                    List.nil());

            JCMethodInvocation nextInv = alloc.treeMaker.Apply(List.nil(),
                    alloc.treeMaker.Select(alloc.treeMaker.Ident(iterField.name), alloc.names.fromString("next")),
                    List.nil());

            ListBuffer<JCStatement> last = sub.current;
            last.add(createCallBranchStatement(sub.nextBranch()));

            sub.current.add(alloc.treeMaker.If(hasNextInv, alloc.treeMaker.Block(0, List.of(
                    createAssignResult(nextInv),
                    alloc.treeMaker.Return(null))), null));
        });
    }

    private ListBuffer<JCStatement> withScope(ListBuffer<JCStatement> subCurrent,
            Consumer<GeneratorTransformer> consumer) {
        GeneratorTransformer sub = new GeneratorTransformer(alloc, genClass, subCurrent);
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

        JCIf ifStatement = alloc.treeMaker.If(
                that.cond,
                alloc.treeMaker.Block(0, thenBuf.toList()),
                elseBuf.isEmpty() ? null : alloc.treeMaker.Block(0, elseBuf.toList()));

        if (thenStepped || elseStepped) {
            GeneratorBranch next = nextBranch();

            thenLastBuf.add(createCallBranchStatement(next));
            if (elseLastBuf != null) {
                elseLastBuf.add(createCallBranchStatement(next));
            }
        }

        workCurrent.add(ifStatement);
    }

    private void doConditionalLoop(JCExpression cond, JCStatement body) {
        ListBuffer<JCStatement> bodyBuf = new ListBuffer<>();
        ListBuffer<JCStatement> bodyEndBuf = withScope(bodyBuf, body::accept);

        if (bodyBuf != bodyEndBuf) {
            ListBuffer<JCStatement> last = current;
            GeneratorBranch next = nextBranch();

            last.add(createCallBranchStatement(next));
            bodyEndBuf.add(createCallBranchStatement(next));
        }

        current.add(alloc.treeMaker.WhileLoop(cond, alloc.treeMaker.Block(0, bodyBuf.toList())));
    }

    @Override
    public void visitForeachLoop(JCEnhancedForLoop that) {
        JCMethodInvocation iteratorInv = alloc.treeMaker.Apply(
                List.nil(),
                alloc.treeMaker.Select(that.expr, alloc.names.fromString("iterator")),
                List.nil());
        current = withScope(current, (sub) -> {
            JCVariableDecl iterField = sub.createLocalField(alloc.treeMaker.TypeApply(
                    TreeMakerUtil.createClassName(alloc.treeMaker, alloc.names, "java", "util", "Iterator"),
                    List.of(that.var.vartype)), iteratorInv);

            JCMethodInvocation hasNextInv = alloc.treeMaker.Apply(List.nil(),
                    alloc.treeMaker.Select(alloc.treeMaker.Ident(iterField.name), alloc.names.fromString("hasNext")),
                    List.nil());

            JCMethodInvocation nextInv = alloc.treeMaker.Apply(List.nil(),
                    alloc.treeMaker.Select(alloc.treeMaker.Ident(iterField.name), alloc.names.fromString("next")),
                    List.nil());

            sub.current.add(
                    alloc.treeMaker.Exec(alloc.treeMaker.Assign(alloc.treeMaker.Ident(iterField.name), iteratorInv)));
            that.var.accept(sub);
            sub.doConditionalLoop(hasNextInv, alloc.treeMaker.Block(0, List.of(
                    alloc.treeMaker.Exec(alloc.treeMaker.Assign(alloc.treeMaker.Ident(that.var.name), nextInv)),
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

            sub.doConditionalLoop(that.cond, alloc.treeMaker.Block(0, buf.toList()));
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
            current.add(alloc.treeMaker.Exec(alloc.treeMaker.Assign(alloc.treeMaker.Ident(varName), that.init)));
        }

        scopeVariables.put(that.name, that);
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

        current.add(createAssignStep(Constants.GENERATOR_STEP_FINISH));
        current.add(alloc.treeMaker.Return(null));
    }

    @Override
    public void visitDoLoop(JCDoWhileLoop that) {
        ListBuffer<JCStatement> bodyBuf = new ListBuffer<>();
        ListBuffer<JCStatement> bodyEndBuf = withScope(bodyBuf, that.body::accept);

        if (bodyBuf != bodyEndBuf) {
            ListBuffer<JCStatement> last = current;
            GeneratorBranch next = nextBranch();

            last.add(createCallBranchStatement(next));
            bodyEndBuf.add(createCallBranchStatement(next));
        }

        current.add(alloc.treeMaker.DoLoop(alloc.treeMaker.Block(0, bodyBuf.toList()), that.cond));
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
