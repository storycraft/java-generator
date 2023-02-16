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
        Object obj = new Object();

        synchronized(obj) {
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

            switch (a) {
                default: step(99);

                case 1: step(1);
                case 2: step(3); break;
                case 3: step(4);
                case 4: step(6);
            }
            
            String i = "1";
    
            a += 2;
    
            step(a);
    
            a += 3;
        }

        step(2);

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
        int i = 0;

        do {
            i++;
            if (i % 2 == 0) {
                step(i);
            }
        } while (i > 0 && i < 10);

        stepAll(iter2);
    }

    @Test
    public void testGenerator() {
        Iterator<Integer> testGen = gen2(gen1());
        while (testGen.hasNext()) {
            System.out.println(testGen.next());
        }
    }
}
