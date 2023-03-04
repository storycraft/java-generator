# Java generator
Project moved to https://github.com/storycraft/lombok

Generator(Iterator) builder for Java

Add @Generator annotation to convert method to generator. Method return type must be Iterable or Iterator.

This annotation processor hack into javac internal api to modifiy AST(abstract syntax tree)

## Implemention detail
Annotation processor converts normal method into method returning complex state machine iterator.

## Limitations
1. You cannot use yield inside of synchronized block. The monitor lock cannot be held across method. Use lock object instead.

...

## License
Java generator is licensed under Apache License 2.0

