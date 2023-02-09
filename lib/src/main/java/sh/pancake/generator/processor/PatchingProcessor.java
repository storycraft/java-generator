/*
 * Created on Thu Feb 09 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */

package sh.pancake.generator.processor;

import java.util.Set;

import javax.annotation.processing.Completion;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

@SuppressWarnings("all")
public class PatchingProcessor implements Processor {
    private static final String[] REQUIRED_MODULES = {
            "com.sun.tools.javac.api",
            "com.sun.tools.javac.code",
            "com.sun.tools.javac.file",
            "com.sun.tools.javac.parser",
            "com.sun.tools.javac.processing",
            "com.sun.tools.javac.tree",
            "com.sun.tools.javac.util",
    };

    private static Class<?> actualClass;

    static {
        ProcessorUtil.disableIllegalAccessWarning();
        ProcessorUtil.addOpens(REQUIRED_MODULES);

        try {
            actualClass = Class.forName("sh.pancake.generator.processor.GeneratorProcessor");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Processor actual;

    public PatchingProcessor() {
        try {
            this.actual = (Processor) actualClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Set<String> getSupportedOptions() {
        return actual.getSupportedOptions();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return actual.getSupportedAnnotationTypes();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return actual.getSupportedSourceVersion();
    }

    @Override
    public void init(ProcessingEnvironment processingEnv) {
        actual.init(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return actual.process(annotations, roundEnv);
    }

    @Override
    public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
            ExecutableElement member, String userText) {
        return actual.getCompletions(element, annotation, member, userText);
    }

}
