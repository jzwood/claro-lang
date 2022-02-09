package com.claro.intermediate_representation;

import com.claro.compiler_backends.interpreted.ScopedHeap;
import com.claro.intermediate_representation.statements.ProcedureDefinitionStmt;
import com.claro.intermediate_representation.statements.Stmt;
import com.claro.intermediate_representation.statements.StmtListNode;
import com.claro.intermediate_representation.statements.user_defined_type_def_stmts.UserDefinedTypeDefinitionStmt;
import com.claro.intermediate_representation.types.ClaroTypeException;

public class ProgramNode {
  private final String packageString, generatedClassName;
  private final StmtListNode stmtListNode;

  // TODO(steving) package and generatedClassName should probably be injected some cleaner way since this is a Target::JAVA_SOURCE-only artifact.
  public ProgramNode(
      StmtListNode stmtListNode,
      String packageString,
      String generatedClassName) {
    this.stmtListNode = stmtListNode;
    this.packageString = packageString;
    this.generatedClassName = generatedClassName;
  }

  public StringBuilder generateTargetOutput(Target target) throws IllegalArgumentException {
    ScopedHeap scopedHeap = new ScopedHeap();
    scopedHeap.enterNewScope();
    return generateTargetOutput(target, scopedHeap);
  }

  public StringBuilder generateTargetOutput(Target target, ScopedHeap scopedHeap) throws IllegalArgumentException {
    StringBuilder generatedOutput;
    switch (target) {
      case JAVA_SOURCE:
        generatedOutput = generateJavaSourceOutput(scopedHeap);
        break;
      case REPL:
        // We can't check for unused identifiers in the REPL because we might just not yet have seen the instruction
        // where a given identifier will be used.
        scopedHeap.disableCheckUnused();
        // We're gonna be a bit overly clever and allow fallthrough to the next case just for kicks.
      case INTERPRETED:
        generatedOutput = new StringBuilder().append(generateInterpretedOutput(scopedHeap));
        break;
      default:
        throw new IllegalArgumentException("Unexpected Target: " + target);
    }
    return generatedOutput;
  }

  // TODO(steving) This method needs to be refactored and have lots of its logic lifted up out into the callers which
  // TODO(steving) are the actual CompilerBackend's. Most of what's going on here is legit not an AST node's responsibility.
  protected StringBuilder generateJavaSourceOutput(ScopedHeap scopedHeap) {
    // TODO(steving) These Type + Procedure Discovery phases do things in O(2n) time, we really should structure
    // TODO(steving) the response from the parser better so that it's not just a denormalized list of stmts,
    // TODO(steving) instead it should give a structured list of type defs seperate from procedure defs etc.
    // TYPE DISCOVERY PHASE:
    performTypeDiscoveryPhase(stmtListNode, scopedHeap);

    // PROCEDURE DISCOVERY PHASE:
    performProcedureDiscoveryPhase(stmtListNode, scopedHeap);

    // PROCEDURE TYPE VALIDATION PHASE:
    performProcedureTypeValidationPhase(stmtListNode, scopedHeap);

    // NON-PROCEDURE STATEMENT TYPE VALIDATION PHASE:
    // Validate all types in the entire remaining AST before execution.
    try {
      stmtListNode.assertExpectedExprTypes(scopedHeap);
    } catch (ClaroTypeException e) {
      // Java get the ... out of my way and just let me not pollute the interface with a throws modifier.
      // Also let's be fair that I'm just too lazy to make a new RuntimeException version of the ClaroTypeException for
      // use in the execution stage.
      throw new RuntimeException(e);
    }

    // UNUSED CHECKING PHASE:
    // Manually exit the last observed scope which is the global scope, since nothing else will trigger its exit.
    // BUT, because we need the scope to not be thrown away in the REPL case (since in that case we aren't actually
    // exiting the scope, we're just temporarily bouncing out, with the ScopedHeap as the source of continuity between
    // REPL stmts...) we won't do this if it's the repl case. This only loses us "unused" checking, which is disabled in
    // the REPL anyways.
    if (scopedHeap.checkUnused) {
      // Finalize the type-checking phase. In this special case we explicitly want to maintain all of the top level
      // user-defined type data that we evaluated in the preceding phases, so instead of depending on exiting this last
      // scope to trigger checking unused, we'll just manually check unused, but keep the scope so that we keep the type
      // definitions.
      scopedHeap.checkAllIdentifiersInCurrScopeUsed();
    }

    // CODE GEN PHASE:
    // Now that we've validated that all types are valid, go to town in a fresh scope!
    Node.GeneratedJavaSource programJavaSource =
        stmtListNode.generateJavaSourceOutput(scopedHeap, this.generatedClassName);

    // Just for completeness sake, we'll want to exit this global scope as well just in case there are important checks
    // that get run at that time at the last moment before we give the all good signal.
    scopedHeap.exitCurrScope();

    // Wrap the generated source code with the needed Java boilerplate.
    return genJavaSource(programJavaSource);
  }

  protected Object generateInterpretedOutput(ScopedHeap scopedHeap) {
    // TYPE DISCOVERY PHASE:
    performTypeDiscoveryPhase(stmtListNode, scopedHeap);

    // PROCEDURE DISCOVERY PHASE:
    performProcedureDiscoveryPhase(stmtListNode, scopedHeap);

    // PROCEDURE TYPE VALIDATION PHASE:
    performProcedureTypeValidationPhase(stmtListNode, scopedHeap);

    // Validate all types in the entire remaining AST before execution.
    try {
      // TYPE VALIDATION PHASE:
      stmtListNode.assertExpectedExprTypes(scopedHeap);
    } catch (ClaroTypeException e) {
      // Java get the ... out of my way and just let me not pollute the interface with a throws modifier.
      // Also let's be fair that I'm just too lazy to make a new RuntimeException version of the ClaroTypeException for
      // use in the execution stage.
      throw new RuntimeException(e);
    }

    // Now that we've validated that all types are valid, go to town!
    stmtListNode.generateInterpretedOutput(scopedHeap);

    // There's no output in the interpreting mode.
    return null;
  }

  private void performTypeDiscoveryPhase(StmtListNode stmtListNode, ScopedHeap scopedHeap) {
    // Very first things first, because we want to allow references of user-defined types anywhere in the scope
    // regardless of what line the type was defined on, we need to first explicitly and pick all of the user-defined
    // type definition stmts and do a first pass of registering their TypeProviders in the symbol table. These
    // TypeProviders will be resolved recursively in the immediately following type-validation phase.
    StmtListNode currStmtListNode = stmtListNode;
    while (currStmtListNode != null) {
      Stmt currStmt = (Stmt) currStmtListNode.getChildren().get(0);
      if (currStmt instanceof UserDefinedTypeDefinitionStmt) {
        ((UserDefinedTypeDefinitionStmt) currStmt).registerTypeProvider(scopedHeap);
      }
      currStmtListNode = currStmtListNode.tail;
    }
  }

  private void performProcedureDiscoveryPhase(StmtListNode stmtListNode, ScopedHeap scopedHeap) {
    // Very first things first, because we want to allow references of user-defined types anywhere in the scope
    // regardless of what line the type was defined on, we need to first explicitly and pick all of the user-defined
    // type definition stmts and do a first pass of registering their TypeProviders in the symbol table. These
    // TypeProviders will be resolved recursively in the immediately following type-validation phase.
    StmtListNode currStmtListNode = stmtListNode;
    while (currStmtListNode != null) {
      Stmt currStmt = (Stmt) currStmtListNode.getChildren().get(0);
      if (currStmt instanceof ProcedureDefinitionStmt) {
        ((ProcedureDefinitionStmt) currStmt).registerProcedureTypeProvider(scopedHeap);
      }
      currStmtListNode = currStmtListNode.tail;
    }
  }

  private void performProcedureTypeValidationPhase(StmtListNode stmtListNode, ScopedHeap scopedHeap) {
    // Very first things first, because we want to allow references of user-defined types anywhere in the scope
    // regardless of what line the type was defined on, we need to first explicitly and pick all of the user-defined
    // type definition stmts and do a first pass of registering their TypeProviders in the symbol table. These
    // TypeProviders will be resolved recursively in the immediately following type-validation phase.
    StmtListNode currStmtListNode = stmtListNode;
    while (currStmtListNode != null) {
      Stmt currStmt = (Stmt) currStmtListNode.getChildren().get(0);
      if (currStmt instanceof ProcedureDefinitionStmt) {
        try {
          currStmt.assertExpectedExprTypes(scopedHeap);
        } catch (ClaroTypeException e) {
          // Java get the ... out of my way and just let me not pollute the interface with a throws modifier.
          // Also let's be fair that I'm just too lazy to make a new RuntimeException version of the ClaroTypeException for
          // use in the execution stage.
          throw new RuntimeException(e);
        }
      }
      currStmtListNode = currStmtListNode.tail;
    }
    // Now, force the ScopedHeap into a new Scope, because we want to make it explicit that top-level function
    // definitions live in their own scope and cannot reference variables below.
    scopedHeap.enterNewScope();
  }

  /**
   * In some ways this hardcoded class is basically a standard library for this language.
   *
   * @param stmtListJavaSource
   * @return
   */
  // TODO(steving) Take a higher order structure than just a list for the body, allow the java generation steps to
  // TODO(steving) specify code gen for different parts of the gen'd java file. This is just necessary for hacking
  // TODO(steving) java's nuances as our underlying VM.
  private StringBuilder genJavaSource(Node.GeneratedJavaSource stmtListJavaSource) {
    return new StringBuilder(
        String.format(
            "/*******AUTO-GENERATED: DO NOT MODIFY*******/\n\n" +
            "%s" +
            "\n" +
            "import static com.claro.stdlib.userinput.UserInput.promptUserInput;\n" +
            "\n" +
            "import com.google.auto.value.AutoValue;\n" +
            "import com.google.common.collect.ImmutableList;\n" +
            "import com.google.common.collect.ImmutableMap;\n" +
            "import com.claro.intermediate_representation.types.BaseType;\n" +
            "import com.claro.intermediate_representation.types.ConcreteType;\n" +
            "import com.claro.intermediate_representation.types.Type;\n" +
            "import com.claro.intermediate_representation.types.Types;\n" +
            "import com.claro.intermediate_representation.types.impls.builtins_impls.collections.ClaroList;\n" +
            "import com.claro.intermediate_representation.types.impls.builtins_impls.collections.ClaroTuple;\n" +
            "import com.claro.intermediate_representation.types.impls.builtins_impls.procedures.ClaroConsumerFunction;\n" +
            "import com.claro.intermediate_representation.types.impls.builtins_impls.procedures.ClaroFunction;\n" +
            "import com.claro.intermediate_representation.types.impls.builtins_impls.procedures.ClaroProviderFunction;\n" +
            "import com.claro.intermediate_representation.types.impls.user_defined_impls.ClaroUserDefinedTypeImplementation;\n" +
            "import com.claro.runtime_utilities.ClaroRuntimeUtilities;\n" +
            "import java.util.ArrayList;\n" +
            "import lombok.Builder;\n" +
            "import lombok.Data;\n" +
            "import lombok.EqualsAndHashCode;\n" +
            "import lombok.ToString;\n" +
            "import lombok.Value;\n" +
            "\n\n" +
            "public class %s {\n" +
            "// Static preamble statements first thing.\n" +
            "%s\n\n" +
            "// Now the static definitions.\n" +
            "%s\n\n" +
            "  public static void main(String[] args) {\n" +
            "%s" +
            "  }\n\n" +
            "}",
            this.packageString,
            this.generatedClassName,
            stmtListJavaSource.optionalStaticPreambleStmts().orElse(new StringBuilder()),
            stmtListJavaSource.optionalStaticDefinitions().orElse(new StringBuilder()),
            stmtListJavaSource.javaSourceBody()
        )
    );
  }
}