/*
 * Created on Wed Feb 08 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast.visitor;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;
import sh.pancake.generator.processor.ast.Constants;
import sh.pancake.generator.processor.ast.FieldBuffer;
import sh.pancake.generator.processor.ast.GeneratorBlock;
import sh.pancake.generator.processor.ast.GeneratorState;

public class GeneratorTransformer {
    private final TreeMaker treeMaker;
    private final Names names;
    private final FieldBuffer fieldBuffer;

    private final GeneratorBlock block;
    private ListBuffer<JCStatement> current;

    private GeneratorTransformer(TreeMaker treeMaker, Names names, FieldBuffer fieldBuffer, JCExpression retType) {
        this.treeMaker = treeMaker;
        this.names = names;
        this.fieldBuffer = fieldBuffer;

        this.block = new GeneratorBlock(fieldBuffer.nextPrivateField(
                treeMaker.TypeIdent(TypeTag.INT),
                treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_START)),
                retType);
        this.current = block.nextState().statements;
    }

    public static GeneratorTransformer createRoot(Context cx, FieldBuffer fieldBuffer, JCExpression retType) {
        TreeMaker treeMaker = TreeMaker.instance(cx);
        Names names = Names.instance(cx);

        return new GeneratorTransformer(treeMaker, names, fieldBuffer, retType);
    }

    public GeneratorBlock transform(JCStatement statement) {
        new StatementTransformer().transform(statement);
        current.addAll(createJump(Constants.GENERATOR_STEP_FINISH));

        return block;
    }

    private void withTempVar(JCExpression type, @Nullable JCExpression init, Consumer<JCVariableDecl> consumer) {
        JCVariableDecl decl = fieldBuffer.nextPrivateField(type);

        if (init != null) {
            current.add(
                    treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(decl.name), init)));
        }

        consumer.accept(decl);

        if (decl.vartype instanceof JCPrimitiveTypeTree) {
            return;
        }

        current.add(treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(decl.name), treeMaker.Literal(TypeTag.BOT, null))));
    }

    private JCStatement withNested(JCStatement statement) {
        GeneratorBlock subBlock = new GeneratorTransformer(treeMaker, names, fieldBuffer, block.getResultType())
                .transform(statement);

        return subBlock.createNextStatement(treeMaker, names);
    }

    private JCExpressionStatement createAssignStep(int id) {
        return treeMaker
                .Exec(treeMaker.Assign(treeMaker.Ident(block.getStateFieldName()),
                        treeMaker.Literal(TypeTag.INT, id)));
    }

    private List<JCStatement> createJump(int id) {
        return List.of(
                treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(block.getStateFieldName()),
                        treeMaker.Literal(TypeTag.INT, id))),
                treeMaker.Break(null));
    }

    private GeneratorState switchToNextState() {
        GeneratorState next = block.nextState();
        current = next.statements;
        return next;
    }

    private GeneratorState step(JCExpression stepExpr) {
        ListBuffer<JCStatement> buf = current;
        GeneratorState next = switchToNextState();

        buf.add(createAssignStep(next.id));
        buf.add(treeMaker.Return(stepExpr));

        return next;
    }

    private GeneratorState stepAll(JCExpression stepAllExpr) {
        ListBuffer<JCStatement> buf = current;
        GeneratorState next = switchToNextState();

        withTempVar(treeMaker.TypeApply(
                TreeMakerUtil.createClassName(treeMaker, names, "java", "util", "Iterator"),
                List.of(block.getResultType())),
                stepAllExpr,
                (decl) -> {
                    JCMethodInvocation hasNextInv = treeMaker.Apply(List.nil(),
                            treeMaker.Select(treeMaker.Ident(decl.name), names.fromString("hasNext")),
                            List.nil());

                    JCMethodInvocation nextInv = treeMaker.Apply(List.nil(),
                            treeMaker.Select(treeMaker.Ident(decl.name), names.fromString("next")),
                            List.nil());

                    buf.add(createAssignStep(next.id));

                    current.add(
                            treeMaker.If(hasNextInv, treeMaker.Block(0, List.of(treeMaker.Return(nextInv))), null));
                });

        return next;
    }

    private class StatementTransformer extends Visitor {
        private final StepScanner stepScanner;

        public StatementTransformer() {
            stepScanner = new StepScanner();
        }

        public void transform(JCStatement statement) {
            if (stepScanner.scanStep(statement)) {
                statement.accept(this);
            } else {
                current.add(statement);
            }
        }

        private ListBuffer<JCStatement> withBranch(JCStatement branch) {
            ListBuffer<JCStatement> workCurrent = current;

            ListBuffer<JCStatement> branchBuf = new ListBuffer<>();
            current = branchBuf;

            branch.accept(this);
            ListBuffer<JCStatement> branchEndBuf = current;

            if (branchBuf != branchEndBuf) {
                GeneratorState next = switchToNextState();
                workCurrent.addAll(createJump(next.id));
            } else {
                current = workCurrent;
            }

            return branchBuf;
        }

        private GeneratorState withStateBranch(JCStatement branch, boolean shouldJump) {
            ListBuffer<JCStatement> workCurrent = current;

            GeneratorState body = switchToNextState();
            branch.accept(this);

            GeneratorState next = switchToNextState();

            if (shouldJump) {
                workCurrent.addAll(createJump(next.id));
            }

            return body;
        }

        @Override
        public void visitIf(JCIf that) {
            JCIf ifPart = treeMaker.If(that.cond, null, null);
            current.add(ifPart);
            if (that.elsepart != null) {
                that.elsepart.accept(this);
            }

            ifPart.thenpart = treeMaker.Block(0, withBranch(that.thenpart).toList());
        }

        private void doConditionalLoop(JCExpression cond, JCStatement body, boolean deferredCond) {
            GeneratorState bodyState = withStateBranch(body, !deferredCond);

            current.add(treeMaker.If(cond, treeMaker.Block(0, createJump(bodyState.id)), null));
        }

        @Override
        public void visitForeachLoop(JCEnhancedForLoop that) {
            JCMethodInvocation iteratorInv = treeMaker.Apply(
                    List.nil(),
                    treeMaker.Select(that.expr, names.fromString("iterator")),
                    List.nil());

            withTempVar(treeMaker.TypeApply(
                    TreeMakerUtil.createClassName(treeMaker, names, "java", "util", "Iterator"),
                    List.of(that.var.vartype)), iteratorInv, (decl) -> {
                        JCMethodInvocation hasNextInv = treeMaker.Apply(List.nil(),
                                treeMaker.Select(treeMaker.Ident(decl.name), names.fromString("hasNext")),
                                List.nil());

                        JCMethodInvocation nextInv = treeMaker.Apply(List.nil(),
                                treeMaker.Select(treeMaker.Ident(decl.name), names.fromString("next")),
                                List.nil());

                        that.var.accept(this);

                        doConditionalLoop(hasNextInv, treeMaker.Block(0, List.of(
                                treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(that.var.name), nextInv)),
                                that.body)), false);
                    });
        }

        @Override
        public void visitForLoop(JCForLoop that) {
            for (JCStatement statement : that.init) {
                transform(statement);
            }

            ListBuffer<JCStatement> buf = new ListBuffer<>();
            if (that.body != null) {
                buf.append(that.body);
            }

            buf.addAll(that.step);

            doConditionalLoop(that.cond, treeMaker.Block(0, buf.toList()), false);
        }

        @Override
        public void visitWhileLoop(JCWhileLoop that) {
            doConditionalLoop(that.cond, that.body, false);
        }

        @Override
        public void visitBlock(JCBlock that) {
            ListBuffer<JCStatement> cleanupBuffer = new ListBuffer<>();

            for (JCStatement statement : that.stats) {
                transform(statement);

                if (statement instanceof JCVariableDecl varDecl) {
                    if (varDecl.vartype instanceof JCPrimitiveTypeTree) {
                        continue;
                    }

                    cleanupBuffer.add(treeMaker
                            .Exec(treeMaker.Assign(treeMaker.Ident(varDecl.name),
                                    treeMaker.Literal(TypeTag.BOT, null))));
                }
            }

            current.addAll(cleanupBuffer);
        }

        @Override
        public void visitExec(JCExpressionStatement that) {
            if (that.expr instanceof JCMethodInvocation methodInv && methodInv.args.size() == 1) {
                String method = methodInv.meth.toString();

                if ("step".equals(method)) {
                    step(methodInv.args.head);
                } else if ("stepAll".equals(method)) {
                    stepAll(methodInv.args.head);
                } else {
                    current.add(that);
                }
            } else {
                current.add(that);
            }
        }

        @Override
        public void visitReturn(JCReturn that) {
            current.add(createAssignStep(Constants.GENERATOR_STEP_FINISH));
            current.add(treeMaker.Return(null));
        }

        @Override
        public void visitDoLoop(JCDoWhileLoop that) {
            doConditionalLoop(that.cond, that.body, true);
        }

        @Override
        public void visitSynchronized(JCSynchronized that) {
            current.add(treeMaker.Synchronized(that.lock, treeMaker.Block(0, List.of(withNested(that.body)))));
        }

        @Override
        public void visitSkip(JCSkip that) {
        }

        @Override
        public void visitClassDef(JCClassDecl that) {
            current.add(that);
        }

        @Override
        public void visitTree(JCTree that) {
            throw new RuntimeException(that.getKind() + " is not implemented");
        }
    }
}
