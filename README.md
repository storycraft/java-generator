# Java generator
Generator(Iterator) builder for Java

Add @Generator annotation to convert method to generator. Target method's return type must be Iterator.

This annotation processor hack into javac internal api to modifiy AST(abstract syntax tree)
## Implemention detail
Annotation processor converts normal method into method returning complex state machine iterator.

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

