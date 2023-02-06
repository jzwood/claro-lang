package com.claro.intermediate_representation.statements.contracts;

import com.claro.compiler_backends.interpreted.ScopedHeap;
import com.claro.intermediate_representation.statements.Stmt;
import com.claro.intermediate_representation.types.*;
import com.claro.internal_static_state.InternalStaticStateUtil;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Optional;

public class ContractProcedureSignatureDefinitionStmt extends Stmt {
  public final String procedureName;
  private final ImmutableMap<String, TypeProvider> argTypeProvidersByNameMap;
  private final Optional<TypeProvider> optionalOutputTypeProvider;
  private final Boolean explicitlyAnnotatedBlocking;
  private final Optional<ImmutableList<String>> optionalGenericBlockingOnArgs;
  private Optional<ImmutableSet<Integer>> optionalAnnotatedBlockingGenericOnArgs = Optional.empty();
  private Optional<ImmutableList<String>> optionalGenericTypesList;

  public ImmutableList<GenericSignatureType> resolvedArgTypes;
  public Optional<GenericSignatureType> resolvedOutputType;

  public ContractProcedureSignatureDefinitionStmt(
      String procedureName,
      ImmutableMap<String, TypeProvider> argTypeProvidersByNameMap,
      Optional<TypeProvider> optionalOutputTypeProvider,
      Boolean explicitlyAnnotatedBlocking,
      Optional<ImmutableList<String>> optionalGenericBlockingOnArgs,
      Optional<ImmutableList<String>> optionalGenericTypesList) {
    super(ImmutableList.of());
    this.procedureName = procedureName;
    this.argTypeProvidersByNameMap = argTypeProvidersByNameMap;
    this.optionalOutputTypeProvider = optionalOutputTypeProvider;
    this.explicitlyAnnotatedBlocking = explicitlyAnnotatedBlocking;
    this.optionalGenericBlockingOnArgs = optionalGenericBlockingOnArgs;
    this.optionalGenericTypesList = optionalGenericTypesList;
  }

  @Override
  public void assertExpectedExprTypes(ScopedHeap scopedHeap) throws ClaroTypeException {
    // First, validate that this procedure name isn't already in use in this contract definition.
    String normalizedProcedureName =
        getFormattedInternalContractProcedureName(procedureName);
    Preconditions.checkState(
        !scopedHeap.isIdentifierDeclared(normalizedProcedureName),
        String.format(
            "Unexpected redeclaration of contract procedure %s<%s>::%s.",
            InternalStaticStateUtil.ContractDefinitionStmt_currentContractName,
            String.join(", ", InternalStaticStateUtil.ContractDefinitionStmt_currentContractGenericTypeParamNames),
            this.procedureName
        )
    );

    // Now we just need to resolve the arg types for the non-generic param typed args. The generic param types will
    // be made concrete at contract implementation time.
    if (this.optionalGenericTypesList.isPresent()) {
      for (String genericArgName : this.optionalGenericTypesList.get()) {
        Preconditions.checkState(
            !scopedHeap.isIdentifierDeclared(genericArgName),
            String.format(
                "Generic parameter name `%s` already in use for %s<%s>.",
                genericArgName,
                this.procedureName,
                String.join(", ", this.optionalGenericTypesList.get())
            )
        );
        // Temporarily stash a GenericTypeParam in the symbol table to be fetched by type resolution below.
        scopedHeap.putIdentifierValue(
            genericArgName,
            Types.$GenericTypeParam.forTypeParamName(genericArgName),
            null
        );
        scopedHeap.markIdentifierAsTypeDefinition(genericArgName);
      }
    }
    ImmutableList.Builder<GenericSignatureType> resolvedTypesBuilder = ImmutableList.builder();
    for (Map.Entry<String, TypeProvider> argTypeByName : this.argTypeProvidersByNameMap.entrySet()) {
      Type resolvedArgType = argTypeByName.getValue().resolveType(scopedHeap);
      if (resolvedArgType.baseType().equals(BaseType.$GENERIC_TYPE_PARAM)) {
        resolvedTypesBuilder.add(
            GenericSignatureType.forTypeParamName(
                // All of these back flips of modeling a Type for generic type params was to be able to recover the
                // param name here. Really this is working around a deficiency in the parser.
                ((Types.$GenericTypeParam) resolvedArgType).getTypeParamName()));
      } else {
        resolvedTypesBuilder.add(GenericSignatureType.forResolvedType(resolvedArgType));
      }
    }
    // Preserve the resolved signature types so that we can validate against this for contract impls later on.
    this.resolvedArgTypes = resolvedTypesBuilder.build();
    this.resolvedOutputType = this.optionalOutputTypeProvider.map(
        outputTypeProvider -> {
          Type resolvedOutputType = outputTypeProvider.resolveType(scopedHeap);
          if (resolvedOutputType.baseType().equals(BaseType.$GENERIC_TYPE_PARAM)) {
            return GenericSignatureType.forTypeParamName(
                ((Types.$GenericTypeParam) resolvedOutputType).getTypeParamName());
          } else {
            return GenericSignatureType.forResolvedType(resolvedOutputType);
          }
        });
    // Drop the temporary GenericTypeParams placed in the symbol table just for the sake of the generic args.
    this.optionalGenericTypesList.ifPresent(
        l -> l.forEach(scopedHeap::deleteIdentifierValue)
    );

    // Now put the signature in the scoped heap so that we can validate it's not reused in this contract.
    Type procedureType;
    if (this.resolvedArgTypes.size() > 0) {
      ImmutableList<String> argNames = this.argTypeProvidersByNameMap.keySet().asList();
      this.optionalAnnotatedBlockingGenericOnArgs =
          this.optionalGenericBlockingOnArgs.map(
              l -> l.stream()
                  .map(argNames::indexOf)
                  .collect(ImmutableSet.toImmutableSet()));
      if (this.resolvedOutputType.isPresent()) {
        procedureType = Types.ProcedureType.FunctionType.typeLiteralForArgsAndReturnTypes(
            this.resolvedArgTypes.stream()
                .map(GenericSignatureType::toType)
                .collect(ImmutableList.toImmutableList()),
            this.resolvedOutputType.get().toType(),
            this.explicitlyAnnotatedBlocking,
            this.optionalAnnotatedBlockingGenericOnArgs,
            this.optionalGenericTypesList
        );
      } else { // Consumer.
        procedureType = Types.ProcedureType.ConsumerType.typeLiteralForConsumerArgTypes(
            this.resolvedArgTypes.stream()
                .map(GenericSignatureType::toType)
                .collect(ImmutableList.toImmutableList()),
            this.explicitlyAnnotatedBlocking,
            this.optionalAnnotatedBlockingGenericOnArgs,
            this.optionalGenericTypesList
        );
      }
    } else { // Provider.
      procedureType = Types.ProcedureType.ProviderType.typeLiteralForReturnType(
          this.resolvedOutputType.get().toType(),
          this.explicitlyAnnotatedBlocking
      );
    }
    scopedHeap.putIdentifierValue(normalizedProcedureName, procedureType);
    scopedHeap.markIdentifierUsed(normalizedProcedureName);
  }

  public static String getFormattedInternalContractProcedureName(String procedureName) {
    return String.format(
        "$%s::%s",
        InternalStaticStateUtil.ContractDefinitionStmt_currentContractName,
        procedureName
    );
  }

  Types.ProcedureType getExpectedProcedureTypeForConcreteTypeParams(ImmutableMap<String, Type> concreteTypeParams) {
    ImmutableList.Builder<Type> concreteArgTypesBuilder = ImmutableList.builder();
    for (GenericSignatureType genericSignatureType : this.resolvedArgTypes) {
      concreteArgTypesBuilder.add(
          genericSignatureType
              .getOptionalResolvedType()
              .orElseGet(() -> {
                // Handle the possibility that the contract procedure was actually declared to be generic so it should
                // have generic args in addition to the ones received from the Contract itself.
                String genericTypeName = genericSignatureType.getOptionalGenericTypeParamName().get();
                Type contractConcreteType = concreteTypeParams.get(genericTypeName);
                if (contractConcreteType == null) {
                  return Types.$GenericTypeParam.forTypeParamName(genericTypeName);
                }
                return contractConcreteType;
              }));
    }
    ImmutableList<Type> concreteArgTypes = concreteArgTypesBuilder.build();

    Optional<Type> optionalConcreteReturnType =
        this.resolvedOutputType.map(
            genericSignatureType ->
                genericSignatureType
                    .getOptionalResolvedType()
                    .orElseGet(
                        () -> concreteTypeParams.get(genericSignatureType.getOptionalGenericTypeParamName().get())));

    Types.ProcedureType resType;
    if (concreteArgTypes.size() > 0) {
      if (optionalConcreteReturnType.isPresent()) {
        resType = Types.ProcedureType.FunctionType.typeLiteralForArgsAndReturnTypes(
            concreteArgTypes,
            optionalConcreteReturnType.get(),
            this.explicitlyAnnotatedBlocking,
            this.optionalAnnotatedBlockingGenericOnArgs,
            this.optionalGenericTypesList
        );
      } else {
        resType = Types.ProcedureType.ConsumerType.typeLiteralForConsumerArgTypes(
            concreteArgTypes,
            this.explicitlyAnnotatedBlocking,
            this.optionalAnnotatedBlockingGenericOnArgs,
            this.optionalGenericTypesList
        );
      }
    } else {
      resType = Types.ProcedureType.ProviderType.typeLiteralForReturnType(
          optionalConcreteReturnType.get(), this.explicitlyAnnotatedBlocking);
    }
    return resType;
  }

  @Override
  public GeneratedJavaSource generateJavaSourceOutput(ScopedHeap scopedHeap) {
    return GeneratedJavaSource.forJavaSourceBody(new StringBuilder("/* TODO: IMPLEMENT CONTRACTS */"));
  }

  @Override
  public Object generateInterpretedOutput(ScopedHeap scopedHeap) {
    return null;
  }

  @AutoValue
  public abstract static class GenericSignatureType {
    public abstract Optional<Type> getOptionalResolvedType();

    public abstract Optional<String> getOptionalGenericTypeParamName();

    public static GenericSignatureType forTypeParamName(String name) {
      return new AutoValue_ContractProcedureSignatureDefinitionStmt_GenericSignatureType(
          Optional.empty(), Optional.of(name));
    }

    public static GenericSignatureType forResolvedType(Type resolvedType) {
      return new AutoValue_ContractProcedureSignatureDefinitionStmt_GenericSignatureType(
          Optional.of(resolvedType), Optional.empty());
    }

    public Type toType() {
      if (getOptionalResolvedType().isPresent()) {
        return getOptionalResolvedType().get();
      }
      return Types.$GenericTypeParam.forTypeParamName(getOptionalGenericTypeParamName().get());
    }
  }
}
