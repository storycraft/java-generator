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
        int i = 0;

        do {
            i++;
            if (i % 2 == 0) {
                step(i);
            }
        } while (i > 0 && i < 10);

        try {
            stepAll(iter2);
        } catch (Exception e) {
            System.err.println("Error: " + e);
        }
    }
}
```

Annotation processor generates complex state machine like below. Note illegal names are used to prevent variable name collision.
```java
@Generator
public Iterator<Integer> gen(Iterator<Integer> iter2) {
    return new java.util.Iterator<Integer> () {
        private Integer @0;
        private int @1 = 1;
        private java.util.Iterator<Integer> @4;
        private Integer @2;
        int i = 0;
        private int @3 = 6;

        private void b6() {
            @4 = iter2;
            b7();
        }

        private void b7() {
            if (@4.hasNext()) {
                @2 = @4.next();
                return;
            }
            @4 = null;
            @2 = null;
            @3 = 0;
        }

        private void b1() {
            i = 0;
            b4();
            if (@0 != null) return;
            b5();
        }

        private void b2() {
            b3();
        }

        private void b3() {
            b5();
        }

        private void b4() {
            i++;
            if (i % 2 == 0) {
                @0 = i;
                @1 = 2;
                return;
            }
        }

        private void b5() {
            while (i > 0 && i < 10) {
                b4();
                if (@0 != null) return;
            }
            try {
                try {
                    switch (@3) {
                        case 6:
                            {
                                b6();
                                break;
                            }

                        case 7:
                            {
                                b7();
                                break;
                            }

                        case 0:
                            break;

                        default:
                            throw new java.lang.RuntimeException("Unreachable generator step");

                    }
                } catch (java.lang.Throwable t) {
                    @3 = 0;
                    throw t;
                }
                if (@3 != 0) {
                    @0 = @2;
                    return;
                }
            } catch (Exception e) {
                System.err.println("Error: " + e);
            }
            @0 = null;
            @1 = 0;
        }

        private void b8() {
            try {
                switch (@1) {
                    case 1:
                        {
                            b1();
                            break;
                        }

                    case 2:
                        {
                            b2();
                            break;
                        }

                    case 3:
                        {
                            b3();
                            break;
                        }

                    case 4:
                        {
                            b4();
                            break;
                        }

                    case 5:
                        {
                            b5();
                            break;
                        }

                    case 0:
                        break;

                    default:
                        throw new java.lang.RuntimeException("Unreachable generator step");

                }
            } catch (java.lang.Throwable t) {
                @1 = 0;
                throw t;
            }
        }

        @java.lang.Override
        public boolean hasNext() {
            if (@0 == null) b8();
            return @0 != null;
        }

        @java.lang.Override
        public Integer next() {
            if (@0 == null) {
                b8();
                if (@0 == null) throw new java.util.NoSuchElementException("Called next on finished generator");
            }
            Integer res = @0;
            @0 = null;
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
- [x] try block
  - [x] try
  - [ ] catch
  - [ ] try with resources
- [ ] switch
- [ ] switch expression

... and other syntaxes not mentioned.

## License
Java generator is licensed under Apache License 2.0

