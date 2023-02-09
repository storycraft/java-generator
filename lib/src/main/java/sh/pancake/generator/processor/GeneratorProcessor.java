/*
 * Created on Mon Feb 06 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.Generator;
import sh.pancake.generator.processor.ast.GeneratorBuilder;
import sh.pancake.generator.processor.ast.GeneratorTransformer;

@SupportedAnnotationTypes("sh.pancake.generator.Generator")
public class GeneratorProcessor extends AbstractProcessor {
    private Context cx;
    private JavacTrees trees;
    private TreeMaker treeMaker;
    private Names names;

    private Messager messager;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Generator.class);

        for (Element e : elements) {
            processMethod(trees.getTree((ExecutableElement) e));
        }

        return false;
    }

    @Override
    public synchronized void init(ProcessingEnvironment arg0) {
        super.init(arg0);

        JavacProcessingEnvironment env = (JavacProcessingEnvironment) processingEnv;

        messager = env.getMessager();

        cx = env.getContext();
        treeMaker = TreeMaker.instance(cx);
        trees = JavacTrees.instance(cx);
        names = Names.instance(cx);
    }

    private JCTree.JCExpression extractIteratorType(JCTree.JCMethodDecl method) {
        if (method.restype instanceof JCTree.JCTypeApply returnType) {
            return returnType.arguments.head;
        }

        return treeMaker.Select(treeMaker.Select(treeMaker.Ident(names.fromString("java")), names.fromString("lang")),
                names.fromString("Object"));
    }

    private void processMethod(JCTree.JCMethodDecl method) {
        messager.printMessage(Kind.NOTE, "before method: " + method);

        JCTree.JCExpression iteratorType = extractIteratorType(method);

        GeneratorBuilder builder = new GeneratorBuilder(cx, iteratorType);

        GeneratorTransformer transformer = GeneratorTransformer.createRoot(cx, iteratorType);
        method.body.accept(transformer);
        method.body.stats = List.of(treeMaker.Return(builder.build(transformer.finish())));

        messager.printMessage(Kind.NOTE, "after method: " + method);
    }
}
