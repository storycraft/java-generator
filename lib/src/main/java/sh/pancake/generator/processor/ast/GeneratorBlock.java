/*
 * Created on Fri Feb 10 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor.ast;

import java.util.ArrayList;
import java.util.Collection;

import com.sun.source.tree.CaseTree.CaseKind;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorBlock {
    private final JCVariableDecl stateField;
    public final JCExpression resultType;

    public final Name loopLabel;
    public final Name stateSwitchLabel;

    private int nextStateId;
    private final ArrayList<GeneratorState> states;

    private final ListBuffer<JCVariableDecl> capturedVar;

    public GeneratorBlock(JCVariableDecl stateField, JCExpression resultType, Name loopLabel, Name stateSwitchLabel) {
        this.stateField = stateField;
        this.resultType = resultType;
        this.loopLabel = loopLabel;
        this.stateSwitchLabel = stateSwitchLabel;

        nextStateId = Constants.GENERATOR_STEP_START;
        states = new ArrayList<>();
        capturedVar = new ListBuffer<>();

        capturedVar.add(stateField);
    }

    public Name getStateFieldName() {
        return stateField.name;
    }

    public GeneratorState nextState() {
        GeneratorState next = new GeneratorState(nextStateId++, new ListBuffer<>());
        states.add(next);
        return next;
    }

    public void captureVariable(JCVariableDecl decl) {
        capturedVar.add(decl);
    }

    public void captureAll(Collection<JCVariableDecl> collection) {
        for (JCVariableDecl decl : collection) {
            capturedVar.add(decl);
        }
    }

    public List<JCVariableDecl> capturedList() {
        return capturedVar.toList();
    }

    public JCStatement createNextStatement(TreeMaker treeMaker, Names names) {
        ListBuffer<JCCase> cases = new ListBuffer<>();

        cases.add(treeMaker.Case(CaseKind.STATEMENT,
                List.of(treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH)),
                List.of(treeMaker.Break(loopLabel)),
                null));

        for (GeneratorState state : states) {
            cases.add(treeMaker.Case(CaseKind.STATEMENT,
                    List.of(treeMaker.Literal(TypeTag.INT, state.id)),
                    state.statements.toList(),
                    null));
        }

        cases.add(treeMaker.Case(
                CaseKind.STATEMENT,
                List.of(treeMaker.DefaultCaseLabel()),
                List.of(treeMaker.Throw(treeMaker.NewClass(
                        null,
                        List.nil(),
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "lang",
                                "RuntimeException"),
                        List.of(treeMaker.Literal(Constants.ERR_UNREACHABLE)),
                        null))),
                null));

        return treeMaker.Labelled(
                loopLabel,
                treeMaker.WhileLoop(
                        treeMaker.Literal(TypeTag.BOOLEAN, 1),
                        treeMaker.Try(
                                treeMaker.Block(0,
                                        List.of(treeMaker.Labelled(stateSwitchLabel, treeMaker.Switch(
                                                treeMaker.Ident(stateField.name),
                                                cases.toList())))),
                                List.of(createCatch(treeMaker, names)),
                                null)));
    }

    private JCCatch createCatch(TreeMaker treeMaker, Names names) {
        JCVariableDecl throwableDecl = treeMaker.VarDef(
                treeMaker.Modifiers(0),
                names.fromString("t"),
                TreeMakerUtil.createClassName(treeMaker, names, "java", "lang",
                        "Throwable"),
                null);

        ListBuffer<JCStatement> catchBuf = new ListBuffer<>();

        catchBuf.add(treeMaker.Exec(treeMaker.Assign(
                treeMaker.Ident(stateField.name),
                treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH))));
        catchBuf.add(treeMaker.Throw(treeMaker.Ident(throwableDecl.name)));

        return treeMaker.Catch(throwableDecl, treeMaker.Block(0, catchBuf.toList()));
    }
}
