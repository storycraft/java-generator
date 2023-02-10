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
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.processor.TreeMakerUtil;

public class IteratorBuilder {
    private final ClassMemberAlloc alloc;
    private final GeneratorClass genClass;

    public IteratorBuilder(ClassMemberAlloc alloc, GeneratorClass genClass) {
        this.alloc = alloc;
        this.genClass = genClass;
    }

    private JCAnnotation createOverride() {
        return alloc.treeMaker.Annotation(TreeMakerUtil.createClassName(
                alloc.treeMaker,
                alloc.names, "java", "lang", "Override"),
                List.nil());
    }

    private JCMethodDecl createMethod(
            JCModifiers mods,
            JCExpression retType,
            String name,
            JCBlock block) {
        return alloc.treeMaker.MethodDef(
                mods,
                alloc.names.fromString(name),
                retType,
                List.nil(),
                List.nil(),
                List.nil(),
                block,
                null);
    }

    public JCNewClass build() {
        TreeMaker treeMaker = alloc.treeMaker;
        Names names = alloc.names;

        ListBuffer<JCTree> iteratorBuf = new ListBuffer<>();

        iteratorBuf.add(genClass.resultField);
        iteratorBuf.add(genClass.stateField);
        iteratorBuf.addAll(genClass.fields.values());
        iteratorBuf.addAll(genClass.methods);

        JCModifiers privateModifiers = treeMaker.Modifiers(Flags.PRIVATE);
        JCPrimitiveTypeTree voidType = treeMaker.TypeIdent(TypeTag.VOID);
        for (GeneratorBranch branch : genClass.branches) {
            iteratorBuf.add(alloc.treeMaker.MethodDef(
                    privateModifiers,
                    branch.name,
                    voidType,
                    List.nil(),
                    List.nil(),
                    List.nil(),
                    treeMaker.Block(0, branch.statements.toList()),
                    null));
        }

        JCMethodDecl peekDecl = alloc.createMethod(
                treeMaker.TypeIdent(TypeTag.VOID),
                List.of(genClass.createPeekStatement(alloc)));

        iteratorBuf.add(peekDecl);

        JCStatement callPeekStatement = treeMaker.Exec(treeMaker.Apply(
                List.nil(),
                treeMaker.Ident(peekDecl.name),
                List.nil()));

        JCExpression nullExpr = treeMaker.Literal(TypeTag.BOT, null);

        JCIdent resultFieldIdent = treeMaker.Ident(genClass.resultField.name);

        JCMethodDecl hasNextDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                treeMaker.TypeIdent(TypeTag.BOOLEAN),
                "hasNext",
                treeMaker.Block(0, List.of(
                        treeMaker.If(treeMaker.Binary(Tag.EQ, resultFieldIdent,
                                nullExpr),
                                callPeekStatement,
                                null),
                        treeMaker.Return(treeMaker.Binary(Tag.NE,
                                resultFieldIdent,
                                nullExpr)))));

        iteratorBuf.add(hasNextDecl);

        Name resTempName = names.fromString("res");

        JCMethodDecl nextDecl = createMethod(
                treeMaker.Modifiers(Flags.PUBLIC, List.of(createOverride())),
                genClass.resultField.vartype,
                "next",
                treeMaker.Block(0, List.of(
                        treeMaker.If(
                                treeMaker.Binary(Tag.EQ,
                                        resultFieldIdent,
                                        nullExpr),
                                treeMaker.Block(0,
                                        List.of(callPeekStatement,
                                                treeMaker.If(
                                                        treeMaker.Binary(
                                                                Tag.EQ,
                                                                resultFieldIdent,
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
                        treeMaker.VarDef(treeMaker.Modifiers(0), resTempName, genClass.resultField.vartype,
                                resultFieldIdent),
                        treeMaker.Exec(treeMaker.Assign(
                                resultFieldIdent, nullExpr)),
                        treeMaker.Return(treeMaker.Ident(resTempName)))));

        iteratorBuf.add(nextDecl);

        return treeMaker.NewClass(
                null,
                List.nil(),
                treeMaker.TypeApply(TreeMakerUtil.createClassName(treeMaker, names, "java", "util", "Iterator"),
                        List.of(genClass.resultField.vartype)),
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
}
