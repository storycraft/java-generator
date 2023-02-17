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
        private int @0 = 1;
        private int @1 = 0;
        private int i;
        private java.util.Iterator<Integer> @2;
        private Integer @3;

        private Integer __next() {
            while (true) try {
                switch (@0) {
                case 0:
                    return null;

                case 1:
                    i = 0;

                case 2:
                    i++;
                    if (i % 2 == 0) {
                        @0 = 3;
                        return i;
                    }
                    @0 = 4;
                    break;

                case 3:

                case 4:

                case 5:
                    if (i > 0 && i < 10) {
                        @0 = 2;
                        break;
                    }
                    @2 = iter2;
                    @0 = 6;

                case 6:
                    if (@2.hasNext()) {
                        return @2.next();
                    }
                    @2 = null;
                    @0 = 0;
                    break;

                default:
                    throw new java.lang.RuntimeException("Unreachable generator step");

                }
            } catch (java.lang.Throwable t) {
                if (@1 != 0) {
                    @0 = @1;
                    break;
                }
                @0 = 0;
                throw t;
            }
            return null;
        }

        @java.lang.Override
        public boolean hasNext() {
            if (@3 == null) @3 = __next();
            return @3 != null;
        }

        @java.lang.Override
        public Integer next() {
            if (@3 == null) {
                @3 = __next();
                if (@3 == null) throw new java.util.NoSuchElementException("Called next on finished generator");
            }
            Integer res = @3;
            @3 = null;
            return res;
        }
    };
}
```

## Todo
- [x] for
- [x] foreach iterable
- [ ] foreach arrays
- [x] if
- [x] while
- [x] synchronized
- [x] do-while
- [ ] try block
  - [ ] try
  - [ ] catch
  - [ ] try with resources
- [x] switch
- [ ] switch expression
- [x] continue, break
- [x] label
- [ ] Async runtime with await using generator
...

## License
Java generator is licensed under Apache License 2.0

