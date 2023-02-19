/*
 * Created on Mon Feb 06 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator.processor;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Names;

import sh.pancake.generator.Generator;
import sh.pancake.generator.processor.ast.GeneratorBuilder;
import sh.pancake.generator.processor.ast.NameMapper;
import sh.pancake.generator.processor.ast.visitor.GeneratorTransformer;

@SupportedAnnotationTypes("sh.pancake.generator.Generator")
public class GeneratorProcessor extends AbstractProcessor {
    private Context cx;
    private JavacTrees trees;
    private TreeMaker treeMaker;
    private Names names;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Generator.class);

        try {
            for (Element e : elements) {
                processMethod(trees.getTree((ExecutableElement) e));
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot explode method into generator", e);
        }

        return false;
    }

    @Override
    public synchronized void init(ProcessingEnvironment arg0) {
        super.init(arg0);

        JavacProcessingEnvironment env = (JavacProcessingEnvironment) processingEnv;

        cx = env.getContext();
        treeMaker = TreeMaker.instance(cx);
        trees = JavacTrees.instance(cx);
        names = Names.instance(cx);
    }

    private JCExpression extractIteratorType(JCMethodDecl method) {
        if (method.restype instanceof JCTypeApply returnType) {
            return returnType.arguments.head;
        }

        return TreeMakerUtil.createClassName(treeMaker, names, "java", "lang", "Object");
    }

    private JCAnnotation createGeneratedAnnotation() {
        return treeMaker.Annotation(
                TreeMakerUtil.createClassName(treeMaker, names, "javax", "annotation", "processing", "Generated"),
                List.of(treeMaker.Literal(GeneratorProcessor.class.getName())));
    }

    private void processMethod(JCMethodDecl method) {
        JCExpression iteratorType = extractIteratorType(method);
        NameMapper nameMapper = new NameMapper(cx);

        GeneratorTransformer transformer = GeneratorTransformer.createRoot(cx, nameMapper, iteratorType);
        method.mods.annotations = method.mods.annotations.prepend(createGeneratedAnnotation());
        method.body = new GeneratorBuilder(cx, nameMapper, transformer.transform(method.body)).buildMethodBlock();
    }
}
