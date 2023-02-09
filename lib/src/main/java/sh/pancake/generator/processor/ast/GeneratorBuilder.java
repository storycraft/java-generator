/*
 * Created on Wed Feb 08 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */

package sh.pancake.generator.processor.ast;

import com.sun.tools.javac.code.TypeTag;

import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.sun.source.tree.CaseTree.CaseKind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;

public class GeneratorBuilder {
    private TreeMaker treeMaker;
    private Names names;

    private JCVariableDecl resultDecl;
    private JCVariableDecl stepDecl;

    private JCMethodDecl hasNextDecl;
    private JCMethodDecl nextDecl;

    public GeneratorBuilder(Context cx, JCExpression stepType) {
        this.treeMaker = TreeMaker.instance(cx);
        this.names = Names.instance(cx);

        this.resultDecl = createField(Constants.RESULT_VAR_NAME, stepType);
        this.stepDecl = createField(Constants.STEP_VAR_NAME, treeMaker.TypeIdent(TypeTag.INT),
                treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_START));

        JCStatement callPeekStatement = treeMaker.Exec(treeMaker.Apply(List.nil(),
                treeMaker.Ident(names.fromString(Constants.PEEK_METHOD_NAME)), List.nil()));

        JCExpression nullExpr = treeMaker.Literal(TypeTag.BOT, null);

        this.hasNextDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                treeMaker.TypeIdent(TypeTag.BOOLEAN),
                "hasNext",
                treeMaker.Block(0, List.of(
                        treeMaker.If(treeMaker.Binary(Tag.EQ, treeMaker.Ident(resultDecl.name),
                                nullExpr),
                                callPeekStatement,
                                null),
                        treeMaker.Return(treeMaker.Binary(Tag.NE,
                                treeMaker.Ident(resultDecl.name),
                                nullExpr)))));

        Name resTempName = names.fromString("res");

        this.nextDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                resultDecl.vartype,
                "next",
                treeMaker.Block(0, List.of(
                        treeMaker.If(
                                treeMaker.Binary(Tag.EQ,
                                        treeMaker.Ident(resultDecl.name),
                                        nullExpr),
                                treeMaker.Block(0,
                                        List.of(callPeekStatement,
                                                treeMaker.If(
                                                        treeMaker.Binary(
                                                                Tag.EQ,
                                                                treeMaker.Ident(resultDecl.name),
                                                                nullExpr),
                                                        treeMaker.Throw(treeMaker
                                                                .NewClass(null, List
                                                                        .nil(),
                                                                        TreeMakerUtil.createClassName(treeMaker, names,
                                                                                "java",
                                                                                "util",
                                                                                "NoSuchElementException"),
                                                                        List.of(treeMaker
                                                                                .Literal(
                                                                                        Constants.ERR_NEXT_ON_FINISH_MESSAGE)),
                                                                        null)),
                                                        null))),
                                null),
                        treeMaker.VarDef(treeMaker.Modifiers(0), resTempName, stepType,
                                treeMaker.Ident(resultDecl.name)),
                        treeMaker.Exec(treeMaker.Assign(
                                treeMaker.Ident(resultDecl.name), nullExpr)),
                        treeMaker.Return(treeMaker.Ident(resTempName)))));
    }

    private JCVariableDecl createField(String name, JCExpression varType) {
        return createField(name, varType, null);
    }

    private JCVariableDecl createField(String name, JCExpression varType,
            @Nullable JCExpression init) {
        return treeMaker.VarDef(treeMaker.Modifiers(Flags.PRIVATE), names.fromString(name), varType, init);
    }

    private JCAnnotation createOverride() {
        return treeMaker.Annotation(TreeMakerUtil.createClassName(
                treeMaker,
                names, "java", "lang", "Override"),
                List.nil());
    }

    private JCMethodDecl createMethod(
            JCModifiers mods,
            JCExpression retType,
            String name,
            JCBlock block) {
        return treeMaker.MethodDef(
                mods,
                names.fromString(name),
                retType,
                List.nil(),
                List.nil(),
                List.nil(),
                block,
                null);
    }

    public JCNewClass build(GeneratorMap map) {
        ListBuffer<JCTree> iteratorBuf = new ListBuffer<>();
        iteratorBuf.add(resultDecl);
        iteratorBuf.add(stepDecl);

        for (Entry<Name, JCExpression> entry : map.variables.entrySet()) {
            iteratorBuf
                    .add(treeMaker.VarDef(treeMaker.Modifiers(Flags.PRIVATE), entry.getKey(),
                            entry.getValue(), null));
        }

        ListBuffer<JCCase> peekCases = new ListBuffer<>();

        for (GeneratorBranch branch : map.branches) {
            JCMethodDecl methodDecl = treeMaker.MethodDef(treeMaker.Modifiers(Flags.PRIVATE),
                    names.fromString(Constants.BRANCH_METHOD_PREFIX + branch.id),
                    treeMaker.TypeIdent(TypeTag.VOID), List.nil(), List.nil(), List.nil(),
                    treeMaker.Block(0, branch.statements.toList()), null);
            iteratorBuf.add(methodDecl);

            peekCases.add(treeMaker.Case(CaseKind.STATEMENT,
                    List.of(treeMaker.Literal(TypeTag.INT, branch.id)),
                    List.of(treeMaker.Block(0, List.of(treeMaker.Exec(
                            treeMaker.Apply(List.nil(), treeMaker.Ident(methodDecl.name),
                                    List.nil())),
                            treeMaker.Return(null)))),
                    null));
        }

        peekCases.add(treeMaker.Case(CaseKind.STATEMENT,
                List.of(treeMaker.Literal(TypeTag.INT, Constants.GENERATOR_STEP_FINISH)),
                List.of(treeMaker.Block(0, List.of(treeMaker.Return(null)))), null));

        peekCases.add(treeMaker.Case(CaseKind.STATEMENT, List.of(treeMaker.DefaultCaseLabel()),
                List.of(treeMaker.Throw(treeMaker.NewClass(
                        null,
                        List.nil(),
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "lang", "RuntimeException"),
                        List.of(treeMaker.Literal(Constants.ERR_UNREACHABLE)),
                        null))),
                null));

        Name throwableVar = names.fromString("t");

        JCTry peekTryWrapper = treeMaker.Try(
                treeMaker.Block(0,
                        List.of(treeMaker.Switch(treeMaker.Ident(stepDecl.name),
                                peekCases.toList()))),
                List.of(treeMaker.Catch(
                        treeMaker.VarDef(
                                treeMaker.Modifiers(0),
                                throwableVar,
                                TreeMakerUtil.createClassName(treeMaker, names, "java", "lang", "Throwable"),
                                null),
                        treeMaker.Block(0, List.of(
                                treeMaker.Exec(treeMaker.Assign(
                                        treeMaker.Ident(stepDecl.name),
                                        treeMaker.Literal(TypeTag.INT,
                                                Constants.GENERATOR_STEP_FINISH))),
                                treeMaker.Throw(treeMaker.Ident(throwableVar)))))),
                null);

        JCMethodDecl peekDecl = createMethod(
                treeMaker.Modifiers(Flags.PRIVATE),
                treeMaker.TypeIdent(TypeTag.VOID),
                Constants.PEEK_METHOD_NAME,
                treeMaker.Block(0, List.of(peekTryWrapper)));

        iteratorBuf.add(peekDecl);

        return treeMaker.NewClass(
                null,
                List.nil(),
                treeMaker.TypeApply(TreeMakerUtil.createClassName(treeMaker, names, "java", "util", "Iterator"),
                        List.of(resultDecl.vartype)),
                List.nil(),
                buildIteratorDecl(iteratorBuf));
    }

    private JCClassDecl buildIteratorDecl(ListBuffer<JCTree> buf) {
        buf.add(hasNextDecl);
        buf.add(nextDecl);

        return treeMaker.ClassDef(
                treeMaker.Modifiers(0),
                names.empty,
                List.nil(),
                null,
                List.nil(),
                List.nil(),
                buf.toList());
    }
}
