/*
 * Created on Wed Feb 08 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast.visitor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.sun.source.tree.Tree.Kind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import lombok.AllArgsConstructor;
import sh.pancake.generator.processor.StepTag;
import sh.pancake.generator.processor.TreeMakerUtil;
import sh.pancake.generator.processor.ast.Constants;
import sh.pancake.generator.processor.ast.NameAlloc;
import sh.pancake.generator.processor.ast.GeneratorBlock;
import sh.pancake.generator.processor.ast.GeneratorState;

public class GeneratorTransformer {
    private final TreeMaker treeMaker;
    private final Names names;
    private final NameAlloc nameAlloc;

    private final JCModifiers privateModifiers;

    private final GeneratorBlock block;
    private ListBuffer<JCStatement> current;

    private final Map<Name, Label> labelMap;
    @Nullable
    private StepTag defaultBreak;
    @Nullable
    private StepTag defaultContinue;

    private final StatementTransformer statementTransformer;

    private GeneratorTransformer(TreeMaker treeMaker, Names names, NameAlloc nameAlloc, JCExpression retType) {
        this.treeMaker = treeMaker;
        this.names = names;
        this.nameAlloc = nameAlloc;

        privateModifiers = treeMaker.Modifiers(Flags.PRIVATE);

        block = new GeneratorBlock(
                treeMaker.VarDef(
                        privateModifiers,
                        nameAlloc.nextName(),
                        treeMaker.TypeIdent(TypeTag.INT),
                        treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_START)),
                retType,
                nameAlloc.nextName(),
                nameAlloc.nextName());
        current = block.nextState().statements;

        labelMap = new HashMap<>();
        defaultBreak = null;
        defaultContinue = null;

        statementTransformer = new StatementTransformer();
    }

    public static GeneratorTransformer createRoot(Context cx, NameAlloc nameAlloc, JCExpression retType) {
        TreeMaker treeMaker = TreeMaker.instance(cx);
        Names names = Names.instance(cx);

        return new GeneratorTransformer(treeMaker, names, nameAlloc, retType);
    }

    public GeneratorBlock transform(JCStatement statement) {
        statementTransformer.transform(statement);
        current.addAll(createJump(createStepTag(Constants.GENERATOR_STEP_FINISH)));

        return block;
    }

    private void withTempVar(JCExpression type, @Nullable JCExpression init, Consumer<JCVariableDecl> consumer) {
        JCVariableDecl decl = treeMaker.VarDef(privateModifiers, nameAlloc.nextName(), type, null);
        block.captureVariable(decl);

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

    private void withNested(JCStatement statement, Consumer<JCStatement> consumer) {
        StepTag nextTag = createStepTag();
        current.add(createAssignStep(nextTag));

        GeneratorState next = switchToNextState();
        nextTag.setStep(next.id);

        GeneratorTransformer sub = new GeneratorTransformer(treeMaker, names, nameAlloc, block.resultType);
        GeneratorBlock subBlock = sub.transform(statement);

        block.captureAll(subBlock.capturedList());

        consumer.accept(subBlock.createNextStatement(treeMaker, names));
    }

    private JCExpressionStatement createAssignStep(StepTag tag) {
        return treeMaker
                .Exec(treeMaker.Assign(treeMaker.Ident(block.getStateFieldName()), tag.getLiteral()));
    }

    private List<JCStatement> createJump(StepTag tag) {
        return List.of(
                treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(block.getStateFieldName()), tag.getLiteral())),
                treeMaker.Break(block.stateSwitchLabel));
    }

    private StepTag createStepTag() {
        return createStepTag(-1);
    }

    private StepTag createStepTag(int id) {
        return StepTag.create(treeMaker, id);
    }

    private GeneratorState switchToNextState() {
        GeneratorState next = block.nextState();
        current = next.statements;
        return next;
    }

    private void step(JCExpression stepExpr) {
        ListBuffer<JCStatement> buf = current;
        GeneratorState next = switchToNextState();

        buf.add(createAssignStep(createStepTag(next.id)));
        buf.add(treeMaker.Return(stepExpr));
    }

    private void stepAll(JCExpression stepAllExpr) {
        StepTag bodyTag = createStepTag();
        current.add(createAssignStep(bodyTag));
        withTempVar(treeMaker.TypeApply(
                TreeMakerUtil.createClassName(treeMaker, names, "java", "util", "Iterator"),
                List.of(block.resultType)),
                stepAllExpr,
                (decl) -> {
                    GeneratorState next = switchToNextState();
                    bodyTag.setStep(next.id);

                    JCMethodInvocation hasNextInv = treeMaker.Apply(List.nil(),
                            treeMaker.Select(treeMaker.Ident(decl.name), names.fromString("hasNext")),
                            List.nil());

                    JCMethodInvocation nextInv = treeMaker.Apply(List.nil(),
                            treeMaker.Select(treeMaker.Ident(decl.name), names.fromString("next")),
                            List.nil());

                    current.add(
                            treeMaker.If(hasNextInv, treeMaker.Block(0, List.of(treeMaker.Return(nextInv))), null));
                });
    }

    private class StatementTransformer extends Visitor {
        private final StepScanner stepScanner;

        public StatementTransformer() {
            stepScanner = new StepScanner();
        }

        public void transform(JCStatement statement) {
            if (mustHandle(statement) || stepScanner.scanStep(statement)) {
                statement.accept(this);
            } else {
                current.add(statement);
            }
        }

        private boolean mustHandle(JCStatement statement) {
            return statement.getKind() == Kind.BREAK || statement.getKind() == Kind.CONTINUE;
        }

        private ListBuffer<JCStatement> withBranch(JCStatement branch) {
            ListBuffer<JCStatement> workCurrent = current;

            ListBuffer<JCStatement> branchBuf = new ListBuffer<>();
            current = branchBuf;

            transform(branch);
            ListBuffer<JCStatement> branchEndBuf = current;

            if (branchBuf != branchEndBuf) {
                GeneratorState next = switchToNextState();
                workCurrent.addAll(createJump(createStepTag(next.id)));
            } else {
                current = workCurrent;
            }

            return branchBuf;
        }

        @Override
        public void visitIf(JCIf that) {
            JCIf ifPart = treeMaker.If(that.cond, null, null);
            current.add(ifPart);
            if (that.elsepart != null) {
                transform(that.elsepart);
            }

            ifPart.thenpart = treeMaker.Block(0, withBranch(that.thenpart).toList());
        }

        private void doConditionalLoop(JCExpression cond, JCStatement body, boolean deferredCond) {
            StepTag bodyTag = createStepTag();

            StepTag nextTag = defaultContinue = createStepTag();
            StepTag endTag = defaultBreak = createStepTag();

            if (!deferredCond) {
                current.addAll(createJump(nextTag));
            }

            bodyTag.setStep(switchToNextState().id);
            body.accept(this);

            nextTag.setStep(switchToNextState().id);
            current.add(treeMaker.If(cond,
                    treeMaker.Block(0, createJump(bodyTag)), null));

            endTag.setStep(switchToNextState().id);

            defaultContinue = null;
            defaultBreak = null;
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
                if (statement instanceof JCVariableDecl varDecl) {
                    statement.accept(this);

                    if (varDecl.vartype instanceof JCPrimitiveTypeTree) {
                        continue;
                    }

                    cleanupBuffer.add(treeMaker
                            .Exec(treeMaker.Assign(treeMaker.Ident(varDecl.name),
                                    treeMaker.Literal(TypeTag.BOT, null))));
                    continue;
                }

                transform(statement);
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
            current.add(createAssignStep(createStepTag(Constants.GENERATOR_STEP_FINISH)));
            current.add(treeMaker.Return(null));
        }

        @Override
        public void visitDoLoop(JCDoWhileLoop that) {
            doConditionalLoop(that.cond, that.body, true);
        }

        @Override
        public void visitLabelled(JCLabeledStatement that) {
            Label label = new Label(createStepTag(switchToNextState().id), createStepTag());
            Name name = that.label;

            labelMap.put(name, label);

            that.body.accept(this);

            GeneratorState currentState = block.currentState();
            if (currentState != null) {
                label.end.setStep(currentState.id);
            }

            labelMap.remove(name);
        }

        @Override
        public void visitSynchronized(JCSynchronized that) {
            withNested(that.body, (statement) -> {
                current.add(treeMaker.Synchronized(that.lock, treeMaker.Block(0, List.of(statement))));
            });
        }

        @Override
        public void visitVarDef(JCVariableDecl that) {
            block.captureVariable(treeMaker.VarDef(privateModifiers, that.name, that.vartype, null));
            if (that.init != null) {
                transform(treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(that.name), that.init)));
            }
        }

        @Override
        public void visitSwitch(JCSwitch that) {
            ListBuffer<JCStatement> buf = current;
            StepTag endTag = defaultBreak = createStepTag();

            ListBuffer<JCCase> cases = new ListBuffer<>();

            boolean containsDefault = false;
            for (JCCase switchCase : that.cases) {
                if (!containsDefault) {
                    label: for (JCCaseLabel label : switchCase.labels) {
                        if (label instanceof JCDefaultCaseLabel) {
                            containsDefault = true;
                            break label;
                        }
                    }
                }

                StepTag tag = createStepTag(switchToNextState().id);
                for (JCStatement statement : switchCase.stats) {
                    transform(statement);
                }

                cases.add(treeMaker.Case(
                        switchCase.caseKind,
                        switchCase.labels,
                        createJump(tag),
                        null));
            }

            buf.add(treeMaker.Switch(that.selector, cases.toList()));
            if (!containsDefault) {
                buf.addAll(createJump(endTag));
            }

            GeneratorState end = switchToNextState();
            endTag.setStep(end.id);

            defaultBreak = null;
        }

        @Override
        public void visitSkip(JCSkip that) {
        }

        @Override
        public void visitClassDef(JCClassDecl that) {
            current.add(that);
        }

        @Override
        public void visitContinue(JCContinue that) {
            if (that.label != null) {
                Label label = labelMap.get(that.label);
                if (label != null) {
                    current.addAll(createJump(label.start));
                }

                return;
            }

            current.addAll(createJump(defaultContinue));
        }

        @Override
        public void visitBreak(JCBreak that) {
            if (that.label != null) {
                Label label = labelMap.get(that.label);
                if (label != null) {
                    current.addAll(createJump(label.end));
                }

                return;
            }

            current.addAll(createJump(defaultBreak));
        }

        @Override
        public void visitTree(JCTree that) {
            throw new RuntimeException(that.getKind() + " is not implemented");
        }
    }

    @AllArgsConstructor
    private static class Label {
        public final StepTag start;
        public final StepTag end;
    }
}
