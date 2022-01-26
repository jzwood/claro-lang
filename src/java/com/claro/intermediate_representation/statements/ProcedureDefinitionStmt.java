package com.claro.intermediate_representation.statements;

import com.claro.ClaroParserException;
import com.claro.compiler_backends.interpreted.ScopedHeap;
import com.claro.intermediate_representation.expressions.Expr;
import com.claro.intermediate_representation.types.ClaroTypeException;
import com.claro.intermediate_representation.types.Type;
import com.claro.intermediate_representation.types.TypeProvider;
import com.claro.intermediate_representation.types.Types;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public abstract class ProcedureDefinitionStmt extends Stmt {

  private static final String HIDDEN_RETURN_TYPE_VARIABLE_FLAG_NAME = "$RETURNS";

  private final String procedureName;
  private final Optional<ImmutableMap<String, TypeProvider>> optionalArgTypeProvidersByNameMap;
  public final TypeProvider procedureTypeProvider;
  private Optional<ImmutableMap<String, Type>> optionalArgTypesByNameMap;
  private Types.ProcedureType resolvedProcedureType;

  public ProcedureDefinitionStmt(
      String procedureName,
      ImmutableMap<String, TypeProvider> argTypeProviders,
      TypeProvider procedureTypeProvider,
      StmtListNode procedureBodyStmtListNode) {
    super(ImmutableList.of(procedureBodyStmtListNode));
    this.procedureName = procedureName;
    this.optionalArgTypeProvidersByNameMap = Optional.of(argTypeProviders);
    this.procedureTypeProvider = procedureTypeProvider;
  }

  public ProcedureDefinitionStmt(
      String procedureName,
      TypeProvider procedureTypeProvider,
      StmtListNode procedureBodyStmtListNode) {
    super(ImmutableList.of(procedureBodyStmtListNode));
    this.procedureName = procedureName;
    this.optionalArgTypeProvidersByNameMap = Optional.empty();
    this.procedureTypeProvider = procedureTypeProvider;
  }

  // Register this procedure's type during the procedure resolution phase so that functions may reference each
  // other out of declaration order, to support things like mutual recursion.
  public void registerProcedureTypeProvider(ScopedHeap scopedHeap) {
    // Get the resolved procedure type.
    this.resolvedProcedureType =
        (Types.ProcedureType) this.procedureTypeProvider.resolveType(scopedHeap);
    this.optionalArgTypesByNameMap = this.optionalArgTypeProvidersByNameMap.map(
        argTypeProvidersByNameMap ->
            argTypeProvidersByNameMap.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> entry.getValue().resolveType(scopedHeap))));

    // Validate that this is not a redeclaration of an identifier.
    Preconditions.checkState(
        !scopedHeap.isIdentifierDeclared(this.procedureName),
        String.format("Unexpected redeclaration of %s %s.", resolvedProcedureType, this.procedureName)
    );

    // Finally mark the function declared and initialized within the original calling scope.
    scopedHeap.observeIdentifier(this.procedureName, resolvedProcedureType);
    scopedHeap.initializeIdentifier(this.procedureName);
  }

  @Override
  public void assertExpectedExprTypes(ScopedHeap scopedHeap) throws ClaroTypeException {
    // this::registerTypeProvider should've already been called during the procedure resolution phase, so we
    // can now already assume that this type is registered in this scope.

    // Enter the new scope for this procedure.
    scopedHeap.observeNewScope(false);

    // Now that we're in the procedure's scope, let's allow ReturnStmts temporarily.
    ReturnStmt.enterProcedureScope(this.resolvedProcedureType.hasReturnValue());
    // There's some setup we'll need to do if this procedure expects an output.
    if (this.resolvedProcedureType.hasReturnValue()) {
      // We'll abuse the variable usage checking implementation to validate that we've put a ReturnStmt
      // along every branch of the procedure. So we'll put a hidden variable in the procedure's Scope
      // and then everywhere where a ReturnStmt is located, we'll mark that variable as "initialized".
      // By this approach, we simply need to check if that hidden variable "is initialized" on the last
      // line of the function, and if it's not, then we know there must be a ReturnStmt missing! Damn,
      // sometimes I even impress myself with my laziness...err creativity....
      scopedHeap.observeIdentifier(HIDDEN_RETURN_TYPE_VARIABLE_FLAG_NAME, Types.BOOLEAN);
    }

    // I may need to mark the args as observed identifiers within this new scope.
    if (resolvedProcedureType.hasArgs()) {
      this.optionalArgTypesByNameMap.get().forEach(
          (argName, argType) ->
          {
            scopedHeap.observeIdentifierAllowingHiding(argName, argType);
            scopedHeap.initializeIdentifier(argName);
          });
    }

    // Now from here step through the function body. Just assert expected types on the StmtListNode.
    ((StmtListNode) this.getChildren().get(0)).assertExpectedExprTypes(scopedHeap);

    // Just before we leave the procedure body, let's make sure that we check for required returns.
    if (this.resolvedProcedureType.hasReturnValue()) {
      if (!scopedHeap.isIdentifierInitialized(HIDDEN_RETURN_TYPE_VARIABLE_FLAG_NAME)) {
        // The hidden variable marking whether or not this procedure returns along every branch is
        // uninitialized meaning that there's guaranteed to be a missing return somewhere.
        throw new ClaroParserException(
            String.format("Missing return in %s %s.", this.resolvedProcedureType, this.procedureName));
      }
      // Just to get the compiler not to yell about our hidden variable being unused, mark it used.
      scopedHeap.markIdentifierUsed(HIDDEN_RETURN_TYPE_VARIABLE_FLAG_NAME);
    }

    // Leave the function body.
    scopedHeap.exitCurrObservedScope(false);
    // Now that we've left the procedure's scope, let's disallow ReturnStmts again.
    ReturnStmt.exitProcedureScope();
  }

  @Override
  public GeneratedJavaSource generateJavaSourceOutput(ScopedHeap scopedHeap) {
    scopedHeap.putIdentifierValue(this.procedureName, this.resolvedProcedureType);
    scopedHeap.initializeIdentifier(this.procedureName);

    scopedHeap.enterNewScope();

    // Since we're about to immediately execute some java source code gen, we might need to init the local arg variables.
    Optional<StringBuilder> optionalJavaSourceBodyBuilder = Optional.empty();
    if (this.resolvedProcedureType.hasArgs()) {
      ImmutableSet<Map.Entry<String, Type>> argTypesByNameEntrySet = this.optionalArgTypesByNameMap.get().entrySet();
      argTypesByNameEntrySet.stream()
          .forEach(stringTypeEntry -> {
            // Since we don't have a value to store in the ScopedHeap we'll manually ack that the identifier is init'd.
            scopedHeap.putIdentifierValue(stringTypeEntry.getKey(), stringTypeEntry.getValue());
            scopedHeap.initializeIdentifier(stringTypeEntry.getKey());
          });
      // We need to gen code for initializing args to be used within the java source function body, since we're
      // constrained to the java source function taking args as `Object... args`.
      StringBuilder javaSourceBodyBuilder = new StringBuilder();
      ImmutableList<Map.Entry<String, Type>> argsEntrySet = argTypesByNameEntrySet.asList();
      for (int i = 0; i < argsEntrySet.size(); i++) {
        String argJavaSourceType = argsEntrySet.get(i).getValue().getJavaSourceType();
        javaSourceBodyBuilder.append(
            String.format(
                "%s %s = (%s) args[%s];\n",
                argJavaSourceType,
                argsEntrySet.get(i).getKey(),
                argJavaSourceType,
                i
            )
        );
      }
      optionalJavaSourceBodyBuilder = Optional.of(javaSourceBodyBuilder);
    }

    // There's a StmtListNode to generate code for.
    GeneratedJavaSource procedureBodyGeneratedJavaSource =
        ((StmtListNode) this.getChildren().get(0)).generateJavaSourceOutput(scopedHeap);
    String javaSourceOutput =
        this.resolvedProcedureType.getJavaNewTypeDefinitionStmt(
            this.procedureName,
            optionalJavaSourceBodyBuilder.orElse(new StringBuilder())
                .append(procedureBodyGeneratedJavaSource.javaSourceBody())
        );
    Optional<StringBuilder> optionalStaticDefinitions =
        procedureBodyGeneratedJavaSource.optionalStaticDefinitions();

    scopedHeap.exitCurrScope();

    return GeneratedJavaSource.forStaticDefinitionsAndPreamble(
        optionalStaticDefinitions
            .orElse(new StringBuilder())
            .append(javaSourceOutput),
        new StringBuilder(this.resolvedProcedureType.getStaticFunctionReferenceDefinitionStmt(this.procedureName))
    );
  }

  @Override
  public Object generateInterpretedOutput(ScopedHeap definitionTimeScopedHeap) {
    // Within this function's new scope we'll need to add nodes to declare+init the arg vars within this scope. Do this
    // in order (which means constructing from the tail, up) for no reason other than sanity if we ever look close.
    //
    // Note that if you look closely and squint this below is actually dynamic code generation in Java. Like...duh cuz
    // this whole thing is code gen...that's what a compiler is...but this feels to be more code gen-y so ~shrug~ lol.
    // I think that's neat ;P.
    final BiConsumer<ImmutableList<Expr>, ScopedHeap> defineArgIdentifiersConsumerFn =
        (args, callTimeScopedHeap) -> {
          ImmutableList<Map.Entry<String, Type>> argTypes =
              this.optionalArgTypesByNameMap.get().entrySet().asList();
          for (int i = args.size() - 1; i >= 0; i--) {
            Map.Entry<String, Type> currTailArg = argTypes.get(i);
            new DeclarationStmt(
                currTailArg.getKey(),
                TypeProvider.ImmediateTypeProvider.of(currTailArg.getValue()),
                args.get(i),
                true
            ).generateInterpretedOutput(callTimeScopedHeap);
          }
        };

    definitionTimeScopedHeap.putIdentifierValue(
        this.procedureName,
        this.resolvedProcedureType,
        this.resolvedProcedureType.new ProcedureWrapper() {
          @Override
          public Object apply(ImmutableList<Expr> args, ScopedHeap callTimeScopedHeap) {
            // First things first, this function needs to operate within a totally new scope. NOTE that when this
            // actually finally EXECUTES, because it depends on the time when the function is finally CALLED rather than
            // this current moment where it's defined, the ScopedHeap very likely has additional identifiers present
            // that would not have been intuitive from the source code's definition order... but this is actually ok,
            // the expected scoping semantics are actually ensured by the type-checking phase's assertions at function
            // definition time rather than at its call site. So this is just a note to anyone who might notice any
            // weirdness if you're the type to open up a debugger and step through data... don't be that person.
            callTimeScopedHeap.enterNewScope();

            if (ProcedureDefinitionStmt.this.resolvedProcedureType.hasArgs()) {
              // Execute the arg declarations assigning them to their given values.
              defineArgIdentifiersConsumerFn.accept(args, callTimeScopedHeap);
            }

            // Now we need to execute the function body StmtListNode given.
            Object returnValue = ((StmtListNode) ProcedureDefinitionStmt.this.getChildren().get(0))
                .generateInterpretedOutput(callTimeScopedHeap);

            // We're done executing this function body now, so we can exit this function's scope.
            callTimeScopedHeap.exitCurrScope();

            return returnValue;
          }
        }
    );

    // This is just the function definition (Stmt), not the call-site (Expr), return no value.
    return null;
  }
}
