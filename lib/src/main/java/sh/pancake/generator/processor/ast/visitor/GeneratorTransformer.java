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

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeCopier;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import lombok.AllArgsConstructor;
import sh.pancake.generator.processor.StepTag;
import sh.pancake.generator.processor.TreeMakerUtil;
import sh.pancake.generator.processor.ast.Constants;
import sh.pancake.generator.processor.ast.NameMapper;
import sh.pancake.generator.processor.ast.GeneratorBlock;
import sh.pancake.generator.processor.ast.GeneratorState;

public class GeneratorTransformer {
    private final TreeMaker treeMaker;
    private final Names names;
    private final Log log;
    private final NameMapper nameMapper;

    private final JCModifiers internalModifiers;

    private final GeneratorBlock block;
    private ListBuffer<JCStatement> current;

    private final Map<Name, Label> labelMap;
    @Nullable
    private StepTag defaultBreak;
    @Nullable
    private StepTag defaultContinue;

    private final Inner inner;

    private GeneratorTransformer(TreeMaker treeMaker, Names names, Log log, NameMapper nameMapper,
            JCExpression retType) {
        this.treeMaker = treeMaker;
        this.names = names;
        this.log = log;
        this.nameMapper = nameMapper;

        internalModifiers = treeMaker.Modifiers(Flags.PRIVATE);

        block = new GeneratorBlock(
                treeMaker.VarDef(
                        internalModifiers,
                        nameMapper.map(Constants.GENERATOR_STATE),
                        treeMaker.TypeIdent(TypeTag.INT),
                        treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_START)),
                retType,
                nameMapper.map(Constants.GENERATOR_LOOP));
        current = block.nextState().statements;

        labelMap = new HashMap<>();

        inner = new Inner();
    }

    public static GeneratorTransformer createRoot(Context cx, NameMapper nameMapper, JCExpression retType) {
        TreeMaker treeMaker = TreeMaker.instance(cx);
        Names names = Names.instance(cx);
        Log log = Log.instance(cx);

        return new GeneratorTransformer(treeMaker, names, log, nameMapper, retType);
    }

    public GeneratorBlock transform(JCStatement statement) {
        JCStatement copied = new TreeCopier<>(treeMaker).copy(statement);
        new VariableRemapper(nameMapper).translate(copied);

        return transformInner(copied);
    }

    private GeneratorBlock transformInner(JCStatement statement) {
        inner.transform(statement);

        switchToNextState();
        current.add(createAssignStep(createStepTag(Constants.GENERATOR_STEP_FINISH)));
        current.add(treeMaker.Break(block.loopLabel));

        return block;
    }

    private void captureVariable(JCVariableDecl decl) {
        block.captureVariable(treeMaker.VarDef(internalModifiers, decl.name, decl.vartype, null));
    }

    private void withTempVar(JCExpression type, @Nullable JCExpression init, Consumer<JCVariableDecl> consumer) {
        JCVariableDecl decl = treeMaker.VarDef(internalModifiers, nameMapper.map(Constants.GENERATOR_TMP), type, null);
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

    private JCExpressionStatement createAssignStep(StepTag tag) {
        return treeMaker
                .Exec(treeMaker.Assign(treeMaker.Ident(block.getStateFieldName()), tag.getLiteral()));
    }

    private List<JCStatement> createJump(StepTag tag) {
        return List.of(
                treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(block.getStateFieldName()), tag.getLiteral())),
                treeMaker.Continue(block.loopLabel));
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
        StepTag nextTag = createStepTag();
        current.add(createAssignStep(nextTag));
        current.add(treeMaker.Return(stepExpr));

        nextTag.setStep(switchToNextState().id);
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

    private JCStatement nested(JCStatement statement) {
        GeneratorTransformer sub = new GeneratorTransformer(treeMaker, names, log, nameMapper, block.resultType);
        GeneratorBlock subBlock = sub.transformInner(statement);

        block.captureAll(subBlock.capturedList());

        return subBlock.createNextStatement(treeMaker, names);
    }

    private class Inner extends Visitor {
        private final TransformChecker checker;

        public Inner() {
            checker = new TransformChecker();
        }

        public void transform(JCStatement statement) {
            if (checker.shouldTransform(statement)) {
                statement.accept(this);
            } else {
                current.add(statement);
            }
        }

        @Override
        public void visitIf(JCIf that) {
            StepTag endTag = createStepTag();
            JCIf ifPart = treeMaker.If(treeMaker.Unary(Tag.NOT, that.cond), null, null);
            current.add(ifPart);

            transform(that.thenpart);

            if (that.elsepart != null) {
                switchToNextState();
                current.addAll(createJump(endTag));

                ifPart.thenpart = treeMaker.Block(0, createJump(createStepTag(switchToNextState().id)));
                transform(that.elsepart);
            } else {
                ifPart.thenpart = treeMaker.Block(0, createJump(endTag));
            }

            endTag.setStep(switchToNextState().id);
        }

        private void doConditionalLoop(JCExpression cond, JCStatement body, boolean deferredCond) {
            StepTag bodyTag = createStepTag();

            StepTag nextTag = createStepTag();
            StepTag endTag = createStepTag();
            if (defaultContinue == null) {
                defaultContinue = nextTag;
            }

            if (defaultBreak == null) {
                defaultBreak = endTag;
            }

            if (!deferredCond) {
                current.addAll(createJump(nextTag));
            }

            bodyTag.setStep(switchToNextState().id);
            body.accept(this);

            nextTag.setStep(switchToNextState().id);
            current.add(treeMaker.If(cond,
                    treeMaker.Block(0, createJump(bodyTag)), null));

            endTag.setStep(switchToNextState().id);

            if (defaultContinue == nextTag) {
                defaultContinue = null;
            }

            if (defaultBreak == endTag) {
                defaultBreak = null;
            }
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

                if (Constants.GENERATOR_YIELD.equals(method)) {
                    step(methodInv.args.head);
                } else if (Constants.GENERATOR_YIELD_ALL.equals(method)) {
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
            if (that.expr != null) {
                log.rawError(that.pos, "Generator cannot return with value");
                return;
            }

            current.add(createAssignStep(createStepTag(Constants.GENERATOR_STEP_FINISH)));
            current.add(treeMaker.Break(block.loopLabel));
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
            ListBuffer<JCStatement> buf = current;
            ListBuffer<JCStatement> body = new ListBuffer<>();

            current = body;
            transform(that.body);
            if (current != buf) {
                log.rawError(that.pos, "Cannot yield inside of synchronized block");
                return;
            }
            current = buf;

            current.add(treeMaker.Synchronized(that.lock, treeMaker.Block(0, body.toList())));
        }

        @Override
        public void visitVarDef(JCVariableDecl that) {
            captureVariable(that);
            if (that.init != null) {
                current.add(treeMaker.Exec(treeMaker.Assign(treeMaker.Ident(that.name), that.init)));
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
            } else {
                if (defaultContinue == null) {
                    log.rawError(that.pos, "Invalid continue");
                    return;
                }

                current.addAll(createJump(defaultContinue));
            }
        }

        @Override
        public void visitBreak(JCBreak that) {
            if (that.label != null) {
                Label label = labelMap.get(that.label);
                if (label != null) {
                    current.addAll(createJump(label.end));
                }
            } else {
                if (defaultBreak == null) {
                    log.rawError(that.pos, "Invalid break");
                    return;
                }

                current.addAll(createJump(defaultBreak));
            }
        }

        @Override
        public void visitThrow(JCThrow that) {
            current.add(that);
        }

        @Override
        public void visitTry(JCTry that) {
            ListBuffer<JCExpression> resourceBuf = new ListBuffer<>();
            for (JCTree resource : that.resources) {
                if (resource instanceof JCVariableDecl decl) {
                    decl.accept(this);
                    resourceBuf.add(treeMaker.Ident(decl.name));
                } else if (resource instanceof JCExpression expr) {
                    resourceBuf.add(expr);
                } else {
                    log.rawError(resource.pos, "Invalid resource in try statement");
                    return;
                }
            }

            current.add(createAssignStep(createStepTag(switchToNextState().id)));

            JCTry tryStat = treeMaker.Try(null, null, null);
            StepTag finallyTag = createStepTag();

            current.add(tryStat);
            current.addAll(createJump(finallyTag));

            JCBlock bodyBlock = treeMaker.Block(0, List.of(nested(that.body)));
            for (JCExpression resource : resourceBuf) {
                bodyBlock = treeMaker.Block(0, List.of(treeMaker.Try(
                        bodyBlock,
                        List.of(createResourceCatch(resource)),
                        null),
                        createResourceClose(resource)));
            }
            tryStat.body = bodyBlock;

            ListBuffer<JCCatch> catcherBuffer = new ListBuffer<>();
            for (JCCatch catcher : that.catchers) {
                StepTag catchStep = createStepTag();
                captureVariable(catcher.param);

                JCExpression capturedException = treeMaker.Select(treeMaker.Ident(names._this), catcher.param.name);

                catcherBuffer.add(treeMaker.Catch(catcher.param, treeMaker.Block(0, createJump(catchStep).prepend(
                        treeMaker.Exec(treeMaker.Assign(capturedException, treeMaker.Ident(catcher.param.name)))))));

                catchStep.setStep(switchToNextState().id);
                transform(catcher.body);

                switchToNextState();
                current.add(
                        treeMaker.Exec(treeMaker.Assign(capturedException, treeMaker.Literal(TypeTag.BOT, null))));
                current.addAll(createJump(finallyTag));
            }
            tryStat.catchers = catcherBuffer.toList();

            finallyTag.setStep(switchToNextState().id);
        }

        private JCStatement createResourceClose(JCExpression resource) {
            return treeMaker.Exec(treeMaker.Apply(
                    List.nil(),
                    treeMaker.Select(
                            treeMaker.TypeCast(
                                    TreeMakerUtil.createClassName(treeMaker, names, "java", "lang", "AutoCloseable"),
                                    resource),
                            names.close),
                    List.nil()));
        }

        private JCCatch createResourceCatch(JCExpression resource) {
            JCExpression throwableType = TreeMakerUtil.createClassName(treeMaker, names, "java", "lang", "Throwable");

            JCVariableDecl throwableDecl = treeMaker.VarDef(
                    treeMaker.Modifiers(0),
                    nameMapper.map("t"),
                    throwableType,
                    null);

            JCVariableDecl closeThrowableDecl = treeMaker.VarDef(
                    treeMaker.Modifiers(0),
                    nameMapper.map("t"),
                    throwableType,
                    null);

            JCCatch catchCloseSuppressed = treeMaker.Catch(closeThrowableDecl, treeMaker.Block(0, List.of(
                    treeMaker.Exec(treeMaker.Apply(
                            List.nil(),
                            treeMaker.Select(treeMaker.Ident(throwableDecl.name), names.addSuppressed),
                            List.of(treeMaker.Ident(closeThrowableDecl.name)))))));

            return treeMaker.Catch(
                    throwableDecl,
                    treeMaker.Block(0, List.of(
                            treeMaker.If(treeMaker.Binary(Tag.NE, resource, treeMaker.Literal(TypeTag.BOT, null)),
                                    treeMaker.Block(0, List.of(
                                            treeMaker.Try(treeMaker.Block(0, List.of(createResourceClose(resource))),
                                                    List.of(catchCloseSuppressed), null),
                                            treeMaker.Throw(treeMaker.Ident(throwableDecl.name)))),
                                    null))));
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
