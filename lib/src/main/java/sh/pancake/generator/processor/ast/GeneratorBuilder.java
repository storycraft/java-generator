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

    private final FieldBuffer fieldBuffer;
    private final GeneratorBlock block;

    private final JCVariableDecl resultDecl;

    public GeneratorBuilder(Context cx, FieldBuffer fieldBuffer, GeneratorBlock block) {
        treeMaker = TreeMaker.instance(cx);
        names = Names.instance(cx);

        this.fieldBuffer = fieldBuffer;
        this.block = block;

        resultDecl = fieldBuffer.nextPrivateField(block.getResultType());
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

    public JCNewClass build() {
        ListBuffer<JCTree> iteratorBuf = new ListBuffer<>();

        iteratorBuf.addAll(fieldBuffer.build());

        JCModifiers privateModifiers = treeMaker.Modifiers(Flags.PRIVATE);

        JCMethodDecl innerNextDecl = createMethod(
                privateModifiers,
                resultDecl.vartype,
                Constants.GENERATOR_INNER_NEXT,
                treeMaker.Block(0, List.of(
                        block.createNextStatement(treeMaker, names),
                        treeMaker.Return(treeMaker.Literal(TypeTag.BOT, null)))));

        iteratorBuf.add(innerNextDecl);

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

        iteratorBuf.add(hasNextDecl);

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

        iteratorBuf.add(nextDecl);

        return treeMaker.NewClass(
                null,
                List.nil(),
                treeMaker.TypeApply(
                        TreeMakerUtil.createClassName(treeMaker, names, "java", "util",
                                "Iterator"),
                        List.of(resultDecl.vartype)),
                List.nil(),
                treeMaker.ClassDef(
                        treeMaker.Modifiers(0),
                        names.empty,
                        List.nil(),
                        null,
                        List.nil(),
                        List.nil(),
                        iteratorBuf.toList()));
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
