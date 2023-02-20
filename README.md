# Java generator
Generator(Iterator) builder for Java

Add @Generator annotation to convert method to generator. Method return type must be Iterable or Iterator.

This annotation processor hack into javac internal api to modifiy AST(abstract syntax tree)

## Implemention detail
Annotation processor converts normal method into method returning complex state machine iterator.

## Limitations
1. You cannot use yield inside of synchronized block. The monitor lock cannot be held across method. Use lock object instead.

## Todo
- [x] for
- [x] foreach iterable
- [ ] foreach arrays
- [x] if
- [x] while
- [x] synchronized
- [x] do-while
- [ ] try block
  - [x] try
  - [x] catch
  - [ ] finally
  - [ ] try with resources
- [x] switch
- [ ] switch expression
- [x] continue, break
- [x] label
- [ ] Async runtime with await using generator

...

## License
Java generator is licensed under Apache License 2.0

