package com.claro.intermediate_representation.expressions.procedures.methods;

import com.claro.compiler_backends.interpreted.ScopedHeap;
import com.claro.intermediate_representation.expressions.Expr;
import com.claro.intermediate_representation.types.BaseType;
import com.claro.intermediate_representation.types.ClaroTypeException;
import com.claro.intermediate_representation.types.Type;
import com.claro.intermediate_representation.types.Types;
import com.claro.intermediate_representation.types.impls.user_defined_impls.structs.ClaroStruct;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO(steving) Deprecate this hack in favor of actually implementing methods on objects, making the Builder an object.
public class BuilderMethodCallExpr extends Expr {

  private final String builtTypeName;
  final ImmutableMap<String, Expr> setFieldValues;
  private Types.BuilderType builderType;

  private static final ImmutableSet<BaseType> SUPPORTED_BUILD_TYPES =
      ImmutableSet.of(BaseType.STRUCT, BaseType.IMMUTABLE_STRUCT);

  public BuilderMethodCallExpr(String builtTypeName, Supplier<String> currentLine, int currentLineNumber, int startCol, int endCol) {
    super(ImmutableList.of(), currentLine, currentLineNumber, startCol, endCol);
    this.builtTypeName = builtTypeName;
    this.setFieldValues = ImmutableMap.of();
  }

  // TODO(steving) We need to implement methods support for Builder. This is currently all a hack.
  public BuilderMethodCallExpr(String builtTypeName, ImmutableMap<String, Expr> setFieldValues, Supplier<String> currentLine, int currentLineNumber, int startCol, int endCol) {
    super(ImmutableList.of(), currentLine, currentLineNumber, startCol, endCol);
    this.builtTypeName = builtTypeName;
    this.setFieldValues = setFieldValues;
  }

  @Override
  public Type getValidatedExprType(ScopedHeap scopedHeap) throws ClaroTypeException {
    Type actualIdentifierType = scopedHeap.getValidatedIdentifierType(this.builtTypeName);
    if (!SUPPORTED_BUILD_TYPES.contains(actualIdentifierType.baseType())) {
      throw new ClaroTypeException(actualIdentifierType, SUPPORTED_BUILD_TYPES);
    }

    // Ensure that all the fields set are the expected corresponding type.
    for (Map.Entry<String, Expr> entry : setFieldValues.entrySet()) {
      entry.getValue().assertExpectedExprType(
          scopedHeap, ((Types.StructType) actualIdentifierType).getFieldTypes().get(entry.getKey()));
    }

    this.builderType = Types.BuilderType.forStructType((Types.StructType) actualIdentifierType);
    return builderType;
  }

  @Override
  public GeneratedJavaSource generateJavaSourceOutput(ScopedHeap scopedHeap) {
    AtomicReference<GeneratedJavaSource> fieldValsGenJavaSource =
        new AtomicReference<>(GeneratedJavaSource.forJavaSourceBody(new StringBuilder()));

    StringBuilder res = new StringBuilder(
        String.format(
            "%s.builder()%s",
            this.builtTypeName,
            this.setFieldValues.entrySet().stream()
                .map(
                    entry -> {
                      GeneratedJavaSource currFieldGen = entry.getValue().generateJavaSourceOutput(scopedHeap);
                      String currFieldJavaSource = String.format(
                          ".%s(%s)",
                          entry.getKey(),
                          currFieldGen.javaSourceBody().toString()
                      );
                      currFieldGen.javaSourceBody().setLength(0);
                      fieldValsGenJavaSource.set(fieldValsGenJavaSource.get().createMerged(currFieldGen));

                      return currFieldJavaSource;
                    })
                .collect(Collectors.joining(""))
        )
    );

    return GeneratedJavaSource.forJavaSourceBody(res).createMerged(fieldValsGenJavaSource.get());
  }

  @Override
  public Object generateInterpretedOutput(ScopedHeap scopedHeap) {
    ClaroStruct.Builder<?> builder = ClaroStruct.builderForType(this.builderType.getBuiltType());
    for (Map.Entry<String, Expr> entry : this.setFieldValues.entrySet()) {
      try {
        builder.setField(entry.getKey(), entry.getValue().generateInterpretedOutput(scopedHeap));
      } catch (ClaroTypeException e) {
        throw new RuntimeException("Internal Compiler Error: This should've been caught at type-checking.", e);
      }
    }
    return builder;
  }
}
