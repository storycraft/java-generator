# Java generator
Generator(Iterator) builder for Java

This annotation processor hack into javac internal api to modifiy AST(abstract syntax tree)

## Implemention detail
Add @Generator annotation to convert method to generator. Target method's return type must be Iterator.

See example below
```java
import sh.pancake.generator.Generator;

class Example {
    @Generator
    public Iterator<Integer> gen(Iterator<Integer> iter2) {
        // Yield 1
        step(1);

        // Yield every elements in iter2
        stepAll(iter2);
    }
}
```

Annotation processor generates complex state machine like below. Note illegal names are used to prevent variable name collision.
```java
@Generator
public Iterator<Integer> gen(Iterator<Integer> iter2) {
    return new java.util.Iterator<Integer>(){
        
        // Result value
        private Integer 0;

        // State
        private int 1 = 1;

        // Temporary iterator field
        private java.util.Iterator<Integer> @0;

        private void b1() {
            0 = 1;
            1 = 2;
            return;
        }

        private void b2() {
            @0 = iter2;
            1 = 3;
            b3();
        }

        private void b3() {
            if (@0.hasNext()) {
                0 = @0.next();
                return;
            }
            @0 = null;
            1 = 0;
        }

        private void 2() {
            try {
                switch (1) {

                case 1: {
                    b1();
                    return;
                }

                case 2: {
                    b2();
                    return;
                }

                case 3: {
                    b3();
                    return;
                }

                case 0: {
                    return;
                }

                default:
                    throw new java.lang.RuntimeException("Unreachable generator step");
                }
            } catch (java.lang.Throwable t) {
                1 = 0;
                throw t;
            }
        }

        @java.lang.Override
        public boolean hasNext() {
            if (0 == null) 2();
            return 0 != null;
        }

        @java.lang.Override
        public Integer next() {
            if (0 == null) {
                2();
                if (0 == null) throw new java.util.NoSuchElementException("Called next on finished generator");
            }
            Integer res = 0;
            0 = null;
            return res;
        }
    };
}
```

## TODO
- [x] for
- [x] foreach
- [x] if
- [x] while
- [x] synchronized
- [x] do-while
- [ ] try-catch
- [ ] try
- [ ] switch

... and other syntaxes not mentioned.

## License
Java generator is licensed under Apache License 2.0

