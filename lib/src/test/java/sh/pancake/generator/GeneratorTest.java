/*
 * Created on Mon Feb 06 2023
 *
 * Copyright (c) storycraft. Licensed under the Apache Licence 2.0.
 */
package sh.pancake.generator;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

public class GeneratorTest {

    @Generator
    private Iterator<Integer> gen1() {
        int a = 0;

        if (a % 2 == 0) {
            step(a);
        } else {
            step(a++);
        }

        while (a < 10) {
            step(a++);
        }

        for (int i = 0; i < 5; i++) {
        }

        for (int i = 0; i < 5; i++) {
            step(i);
        }

        a += 2;

        step(a);

        a += 3;

        System.out.println("Generator done");
    }

    @Generator
    Iterator<Integer> gen2(Iterator<Integer> iter2) {
        step(100);

        stepAll(iter2);

        step(200);
    }

    @Generator
    public Iterator<Integer> gen(Iterator<Integer> iter2) {
        // Yield 1
        step(1);

        // Yield every elements in iter2
        stepAll(iter2);
    }

    @Test
    public void testGenerator() {
        Iterator<Integer> testGen = gen(gen1());
        while (testGen.hasNext()) {
            System.out.println(testGen.next());
        }
    }
}
