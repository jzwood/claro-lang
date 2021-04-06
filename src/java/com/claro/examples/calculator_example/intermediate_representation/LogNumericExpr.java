package com.claro.examples.calculator_example.intermediate_representation;

import com.claro.examples.calculator_example.CalculatorParserException;
import com.claro.examples.calculator_example.compiler_backends.interpreted.ScopedHeap;
import com.claro.examples.calculator_example.intermediate_representation.types.Type;
import com.claro.examples.calculator_example.intermediate_representation.types.Types;
import com.google.common.collect.ImmutableList;

public class LogNumericExpr extends NumericExpr {

  // TODO(steving) This should only accept other NumericExpr args. Need to update the grammar.
  public LogNumericExpr(Expr arg, Expr log_base) {
    super(ImmutableList.of(arg, log_base));
  }

  @Override
  protected Type getValidatedExprType(ScopedHeap scopedHeap) {
    return Types.DOUBLE;
  }

  @Override
  protected StringBuilder generateJavaSourceOutput(ScopedHeap scopedHeap) {
    return new StringBuilder(
        String.format(
            "(Math.log(%s) / Math.log(%s))",
            this.getChildren().get(0).generateJavaSourceOutput(scopedHeap),
            this.getChildren().get(1).generateJavaSourceOutput(scopedHeap)
        )
    );
  }

  // TODO(steving) This might be the point where switching the compiler implementation to ~Kotlin~ will be a legitimate
  // TODO(steving) win. I believe that Kotlin supports multiple-dispatch which I think would allow this entire garbage
  // TODO(steving) mess of instanceof checks to be reduced to a single function call passing the lhs and rhs, and that
  // TODO(steving) function would have a few different impls taking args of different types and the correct one would be
  // TODO(steving) called. I guess in that case it's just the runtime itself handling these instanceof checks.
  @Override
  protected Object generateInterpretedOutput(ScopedHeap scopedHeap) {
    Object lhs = this.getChildren().get(0).generateInterpretedOutput(scopedHeap);
    Object rhs = this.getChildren().get(1).generateInterpretedOutput(scopedHeap);
    if (lhs instanceof Double && rhs instanceof Double) {
      return Math.log((Double) lhs) / Math.log((Double) rhs);
    } else if (lhs instanceof Integer && rhs instanceof Integer) {
      return Math.log((Integer) lhs) / Math.log((Integer) rhs);
    } else if ((lhs instanceof Integer && rhs instanceof Double) || (lhs instanceof Double && rhs instanceof Integer)) {
      Double lhsDouble;
      Double rhsDouble;
      if (lhs instanceof Integer) {
        lhsDouble = ((Integer) lhs).doubleValue();
        rhsDouble = (Double) rhs;
      } else {
        lhsDouble = (Double) lhs;
        rhsDouble = ((Integer) rhs).doubleValue();
      }
      return Math.log(lhsDouble) / Math.log(rhsDouble);
    } else {
      // TODO(steving) In the future, assume that operator-log is able to be used on arbitrary Comparable impls. So check
      // TODO(steving) the type of each in the heap and see if they are implementing Comparable, and call their impl of
      // TODO(steving) Operators::log.
      throw new CalculatorParserException(
          "Internal Compiler Error: Currently `log_*` is not supported for types other than Integer and Double.");
    }
  }
}
