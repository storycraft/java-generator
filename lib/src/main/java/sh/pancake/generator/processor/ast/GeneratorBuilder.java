/*
 * Created on Wed Feb 08 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */

package sh.pancake.generator.processor.ast;

import com.sun.tools.javac.code.TypeTag;
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
    private final TreeMaker treeMaker;
    private final Names names;

    private final GeneratorBlock block;

    private final JCVariableDecl resultDecl;

    public GeneratorBuilder(Context cx, NameMapper alloc, GeneratorBlock block) {
        treeMaker = TreeMaker.instance(cx);
        names = Names.instance(cx);

        this.block = block;

        resultDecl = treeMaker.VarDef(treeMaker.Modifiers(Flags.PRIVATE),
                alloc.map(Constants.GENERATOR_RESULT), block.resultType, null);
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

    public JCBlock buildMethodBlock() {
        JCClassDecl classDecl = buildClassDecl();

        return treeMaker.Block(0, List.of(
                classDecl,
                treeMaker.Return(treeMaker.NewClass(
                        null,
                        List.nil(),
                        treeMaker.Ident(classDecl.name),
                        List.nil(),
                        null))));
    }

    public JCClassDecl buildClassDecl() {
        ListBuffer<JCTree> classBuf = new ListBuffer<>();

        classBuf.addAll(block.capturedList());
        classBuf.add(resultDecl);

        JCModifiers privateModifiers = treeMaker.Modifiers(Flags.PRIVATE);

        JCMethodDecl innerNextDecl = createMethod(
                privateModifiers,
                resultDecl.vartype,
                Constants.GENERATOR_INNER_NEXT,
                treeMaker.Block(0, List.of(
                        block.createNextStatement(treeMaker, names),
                        treeMaker.Return(treeMaker.Literal(TypeTag.BOT, null)))));

        classBuf.add(innerNextDecl);

        JCMethodInvocation invInnerNext = treeMaker.Apply(
                List.nil(),
                treeMaker.Ident(innerNextDecl.name),
                List.nil());

        JCExpression nullExpr = treeMaker.Literal(TypeTag.BOT, null);

        JCIdent resultFieldIdent = treeMaker.Ident(resultDecl.name);

        JCStatement getResultStatement = treeMaker.Exec(treeMaker.Assign(resultFieldIdent, invInnerNext));
        JCExpression shouldGetCond = treeMaker.Binary(Tag.EQ, resultFieldIdent, nullExpr);

        JCMethodDecl hasNextDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                treeMaker.TypeIdent(TypeTag.BOOLEAN),
                "hasNext",
                treeMaker.Block(0, List.of(
                        treeMaker.If(shouldGetCond,
                                getResultStatement,
                                null),
                        treeMaker.Return(treeMaker.Binary(Tag.NE,
                                resultFieldIdent,
                                nullExpr)))));

        classBuf.add(hasNextDecl);

        Name resTempName = names.fromString("res");

        JCMethodDecl nextDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                resultDecl.vartype,
                "next",
                treeMaker.Block(0, List.of(
                        treeMaker.If(
                                shouldGetCond,
                                treeMaker.Block(0,
                                        List.of(getResultStatement,
                                                treeMaker.If(
                                                        shouldGetCond,
                                                        treeMaker.Throw(createNewNoSuchElementException()),
                                                        null))),
                                null),
                        treeMaker.VarDef(treeMaker.Modifiers(0), resTempName,
                                resultDecl.vartype,
                                resultFieldIdent),
                        treeMaker.Exec(treeMaker.Assign(
                                resultFieldIdent, nullExpr)),
                        treeMaker.Return(treeMaker.Ident(resTempName)))));

        classBuf.add(nextDecl);

        JCMethodDecl iteratorDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                treeMaker.TypeApply(
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "util",
                                "Iterator"),
                        List.of(resultDecl.vartype)),
                "iterator",
                treeMaker.Block(0, List.of(treeMaker.Return(treeMaker.Ident(names._this)))));

        classBuf.add(iteratorDecl);

        return treeMaker.ClassDef(
                treeMaker.Modifiers(Flags.FINAL),
                names.fromString(Constants.GENERATOR_CLASS_NAME),
                List.nil(),
                null,
                List.of(
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "lang",
                                "Iterable"),
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "util",
                                "Iterator")),
                List.nil(),
                classBuf.toList());
    }

    private JCNewClass createNewNoSuchElementException() {
        return treeMaker
                .NewClass(null, List
                        .nil(),
                        TreeMakerUtil.createClassName(treeMaker, names,
                                "java",
                                "util",
                                "NoSuchElementException"),
                        List.of(treeMaker
                                .Literal(
                                        Constants.ERR_NEXT_ON_FINISH_MESSAGE)),
                        null);
    }
}
