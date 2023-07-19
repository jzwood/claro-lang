load("@jflex_rules//jflex:jflex.bzl", "jflex")
load("@jflex_rules//cup:cup.bzl", "cup")

DEFAULT_CLARO_NAME = "claro"
DEFAULT_PACKAGE_PREFIX = "com.claro"

CLARO_STDLIB_FILES = [
    "//src/java/com/claro/stdlib/claro:builtin_functions.claro_internal",
    "//src/java/com/claro/stdlib/claro:builtin_types.claro_internal",
]
CLARO_BUILTIN_JAVA_DEPS = [
    "//:guava",
    "//:gson",
    "//:okhttp",
    "//:retrofit",
    "//src/java/com/claro/stdlib",
    "//src/java/com/claro/intermediate_representation/types/impls:claro_type_implementation",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls/atoms",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls/collections:collections_impls",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls/futures:ClaroFuture",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls/http:http_response",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls/procedures",
    "//src/java/com/claro/intermediate_representation/types/impls/builtins_impls/structs",
    "//src/java/com/claro/intermediate_representation/types/impls/user_defined_impls:user_defined_impls",
    "//src/java/com/claro/intermediate_representation/types:base_type",
    "//src/java/com/claro/intermediate_representation/types:concrete_type",
    "//src/java/com/claro/intermediate_representation/types:supports_mutable_variant",
    "//src/java/com/claro/intermediate_representation/types:type",
    "//src/java/com/claro/intermediate_representation/types:types",
    "//src/java/com/claro/intermediate_representation/types:type_provider",
    "//src/java/com/claro/runtime_utilities",
    "//src/java/com/claro/runtime_utilities/http",
    "//src/java/com/claro/runtime_utilities/http:http_server",
    "//src/java/com/claro/runtime_utilities/injector",
    "//src/java/com/claro/runtime_utilities/injector:key",
    "//src/java/com/claro/stdlib/userinput",
]

def _invoke_claro_compiler_impl(ctx):
    is_module = ctx.file.module_api_file != None
    if is_module:
        srcs = [ctx.file.module_api_file] + ctx.files._stdlib_srcs + ctx.files.srcs
    else:
        # The user told me which .claro file is the main file, however, they may have also included it in the srcs list, so
        # filter it from the srcs. This way the main file is always guaranteed to be the first file in the list.
        srcs = ctx.files._stdlib_srcs + [ctx.file.main_file] + [f for f in ctx.files.srcs if f != ctx.file.main_file]
        classname = ctx.file.main_file.basename[:len(ctx.file.main_file.basename) - len(".claro")]

    # By deriving the project package from the workspace name, this rule's able to ensure that generated Java sources
    # end up using unique Java packages so that it doesn't conflict with any downstream deps.
    project_package = ctx.workspace_name.replace('-', '.').replace('_', '.')

    # Declare an Action to execute the Claro compiler binary over the given srcs.
    # Constructing the args using ctx.actions.args() is Bazel's approach to performance optimization akin to Java's
    # use of StringBuilder rather than immediate String concatenations.
    args = ctx.actions.args()
    args.add("--java_source")
    if is_module:
        args.add("--unique_module_name", ctx.attr.unique_module_name)
    else:
        args.add("--classname", classname)
    if not ctx.attr.debug:
        args.add("--silent")
    args.add("--package", project_package)
    for src in srcs:
        args.add("--src", src)
    dep_module_runfiles = []
    for module_dep_label, concatenated_module_dep_names in ctx.attr.deps.items():
        for module_dep_name in concatenated_module_dep_names.split('$'):
            dep_module_runfiles.append(module_dep_label[DefaultInfo].files.to_list()[0])
            args.add("--dep", module_dep_label.files.to_list()[0], format = "{0}:%s".format(module_dep_name))
    args.add("--output_file_path", ctx.outputs.compiler_out)

    # Add all of the .claro_module files from our module deps targets to the declared inputs that we require Bazel to
    # place in the sandbox for this compilation action.
    inputs = srcs + [t.files.to_list()[0] for t in ctx.attr.deps.keys()]
    ctx.actions.run(
        inputs = inputs,
        outputs = [ctx.outputs.compiler_out],
        arguments = [args],
        progress_message = "Compiling Claro Program: " + ctx.outputs.compiler_out.short_path,
        executable = ctx.executable._claro_compiler,
    )

    if is_module:
        # I actually want to immediately unpack the .claro_module and produce a .java file for the static java codegen of
        # this module. This is *solely* for the sake of this Bazel build being incremental. It's a bit strange for this rule
        # to have just packed this into the .claro_module and then immediately extract it, but for now, my thought process
        # is that this helps to ensure that this .java file is *actually* coming from this compiled module, and not from
        # some external Bazel shenanigans... TODO(steving) This decision should be revisited in the future.
        args = ctx.actions.args()
        args.add("--claro_module_file", ctx.outputs.compiler_out)
        args.add("--static_java_out", ctx.outputs.module_static_java_out)
        ctx.actions.run(
            inputs = [ctx.outputs.compiler_out],
            outputs = [ctx.outputs.module_static_java_out],
            arguments = [args],
            progress_message = "Extracting Module Static Java Codegen: " + ctx.outputs.module_static_java_out.short_path,
            executable = ctx.executable._module_deserialization_util,
        )

    return [
        DefaultInfo(runfiles = ctx.runfiles(files = dep_module_runfiles))
    ]


def claro_binary(name, main_file, srcs = [], deps = {}, debug = False):
    main_file_name = main_file[:len(main_file) - len(".claro")]
    _invoke_claro_compiler(
        name = "{0}_compile".format(name),
        main_file = main_file,
        srcs = srcs,
        deps = _transpose_module_deps_dict(deps),
        compiler_out = "{0}.java".format(main_file_name),
        debug = debug,
    )
    native.java_binary(
        name = name,
        # TODO(steving) I need this package to be derived from the package computed in _invoke_claro_compiler().
        main_class = "claro.lang." + main_file_name,
        srcs = [":{0}.java".format(main_file_name)],
        deps = CLARO_BUILTIN_JAVA_DEPS +
            ["{0}_compiled_claro_module_java_lib".format(dep) for dep in deps.values()],
    )


def claro_module(name, module_api_file, srcs, deps = {}, debug = False, **kwargs):
    # Leveraging Bazel semantics to produce a unique module name from this target's Bazel package.
    # If this target is declared as //src/com/foo/bar:my_module, then the unique_module_name will be set to
    # 'src$com$foo$bar$my_module' which is guaranteed to be a name that's unique across this entire Bazel project.
    unique_module_name = native.package_name().replace('/', '$') + '$' + name

    _invoke_claro_compiler(
        name = "{0}".format(name),
        module_api_file = module_api_file,
        srcs = srcs,
        deps = _transpose_module_deps_dict(deps),
        unique_module_name = unique_module_name,
        compiler_out = "{0}.claro_module".format(name),
        module_static_java_out = "{0}.java".format(unique_module_name),
        debug = debug,
        **kwargs # Default attrs like `visibility` will be set here so that Bazel defaults are honored.
    )
    native.java_library(
        name = "{0}_compiled_claro_module_java_lib".format(name),
        srcs = [":{0}.java".format(unique_module_name)],
        deps = CLARO_BUILTIN_JAVA_DEPS +
            # Dict comprehension just to "uniquify" the dep targets. It's technically completely valid to reuse the same
            # dep more than once for different dep module impls in a claro_* rule.
            {"{0}_compiled_claro_module_java_lib".format(dep): "" for dep in deps.values()}.keys(),
        **kwargs # Default attrs like `visibility` will be set here so that Bazel defaults are honored.
    )

def _transpose_module_deps_dict(deps):
    res = {}
    for module_name, target in deps.items():
        if target in res:
            # Here, in order to allow a claro_*() rule to use the same impl target for multiple different dep modules,
            # I'll use the scheme of concatenating the module names using '$', which is an invalid identifier char in Claro.
            res[target] += "$" + module_name
        else:
            res[target] = module_name
    return res

_invoke_claro_compiler = rule(
    implementation = _invoke_claro_compiler_impl,
    attrs = {
        "main_file": attr.label(
            doc = "The .claro file containing top-level statements to be executed as the program's main entrypoint.\n" +
                  "module_api_file must not be set if this is set.",
            allow_single_file = [".claro"],
            default = None,
        ),
        "module_api_file": attr.label(
            doc = "The .claro_module_api file containing this Module's API.\n" +
                  "main_file must not be set if this is set.",
            allow_single_file = [".claro_module_api"],
            default = None,
        ),
        "srcs": attr.label_list(allow_files = [".claro"]),
        "deps": attr.label_keyed_string_dict(
            doc = "An optional set of Modules that this binary's sources directly depend on.",
            allow_files = [".claro_module"],
            # providers = ... Figure out how to utilize this to get deps from claro_module.
        ),
        "unique_module_name": attr.string(),
        "debug": attr.bool(default = False),
        "_claro_compiler": attr.label(
            default = Label("//src/java/com/claro:claro_compiler_binary"),
            allow_files = True,
            executable = True,
            cfg = "host",
        ),
        "_module_deserialization_util": attr.label(
            default = Label("//src/java/com/claro/compiler_backends/java_source/deserialization:claro_module_deserialization_util"),
            allow_files = True,
            executable = True,
            cfg = "host",
        ),
        "_stdlib_srcs": attr.label_list(
            default = [Label(stdlib_file) for stdlib_file in CLARO_STDLIB_FILES],
            allow_files = [".claro_internal"],
        ),
        "compiler_out": attr.output(
            doc = "The .java source file codegen'd by the Claro compiler. This is an intermediate output produced by " +
                  "this rule, and manually inspecting it should not be necessary for most users.",
            mandatory = True,
        ),
        "module_static_java_out": attr.output(
            doc = "The .java source file that will contain the static java codegen extracted from the .claro_module " +
                  "produced by this rule in order for dependent claro_binary() targets to access it directly for the " +
                  "sake of incrementality.",
            mandatory = False,
        )
    },
)


# This macro produces a target that will allow you to build a claro_builtin_java_deps_deploy.jar that can be used by the
# CLI to compile Claro programs from source w/o using Bazel. This is intended for use in lightweight scripting scenarios
# and is not intended to be the primary form of building Claro programs. Bazel is very much the answer for large scale
# Claro programs (this will be particularly apparent once Claro's module/library system is built out).
def gen_claro_builtin_java_deps_jar():
    # I literally just have no idea how to create this JAR file using Bazel w/o a src-file.
    native.genrule(
        name = "dummy_java_file",
        cmd = "echo \"public class dummy {}\" > $(OUTS)",
        outs = ["dummy.java"]
    )
    native.java_binary(
        name = "claro_builtin_java_deps",
        srcs = [":dummy_java_file"],
        create_executable = False,
        deps = CLARO_BUILTIN_JAVA_DEPS,
        # Pack the stdlib into this JAR file just so that there's one fewer thing for users to download and keep in the
        # right place. This can always be changed later.
        resources = CLARO_STDLIB_FILES
    )


def gen_claro_compiler(name = DEFAULT_CLARO_NAME):
    native.java_binary(
        name = name + "_compiler_binary",
        main_class = DEFAULT_PACKAGE_PREFIX + ".ClaroCompilerMain",
        runtime_deps = [":" + name + "_compiler_main"],
    )

    native.java_library(
        name = name + "_compiler_main",
        srcs = [
            "ClaroCompilerMain.java"
        ],
        deps = [
            ":" + name + "_java_parser",
            "//src/java/com/claro/compiler_backends/interpreted:interpreter",
            "//src/java/com/claro/compiler_backends/java_source:java_source",
            "//src/java/com/claro/compiler_backends/repl:repl",
        ],
    )

    native.java_library(
        name = name + "_java_parser",
        srcs = [
            ":" + name + "_gen_lexer",
            ":" + name + "_gen_parser",
        ],
        deps = [
            ":claro_parser_exception",
            ":lexed_value",
            "//src/java/com/claro/compiler_backends/interpreted:scoped_heap",
            "//src/java/com/claro/intermediate_representation:node",
            "//src/java/com/claro/intermediate_representation:program_node",
            "//src/java/com/claro/intermediate_representation/expressions:expr",
            "//src/java/com/claro/intermediate_representation/expressions:expr_impls",
            "//src/java/com/claro/intermediate_representation/expressions:lambda_expr_impl",
            "//src/java/com/claro/intermediate_representation/expressions:unwrap_user_defined_type_expr_impl",
            "//src/java/com/claro/intermediate_representation/expressions/bool:bool_expr",
            "//src/java/com/claro/intermediate_representation/expressions/bool:bool_expr_impls",
            "//src/java/com/claro/intermediate_representation/expressions/numeric:numeric_expr_impls",
            "//src/java/com/claro/intermediate_representation/expressions/procedures/functions",
            "//src/java/com/claro/intermediate_representation/expressions/term:term",
            "//src/java/com/claro/intermediate_representation/expressions/term:term_impls",
            "//src/java/com/claro/intermediate_representation/statements:stmt",
            "//src/java/com/claro/intermediate_representation/statements:stmt_impls",
            "//src/java/com/claro/intermediate_representation/statements:stmt_list_node",
            "//src/java/com/claro/intermediate_representation/statements/contracts",
            "//src/java/com/claro/intermediate_representation/statements/user_defined_type_def_stmts",
            "//src/java/com/claro/intermediate_representation/types:base_type",
            "//src/java/com/claro/intermediate_representation/types:claro_type_exception",
            "//src/java/com/claro/intermediate_representation/types:supports_mutable_variant",
            "//src/java/com/claro/intermediate_representation/types:type_provider",
            "//src/java/com/claro/intermediate_representation/types:type",
            "//src/java/com/claro/intermediate_representation/types:types",
            "//src/java/com/claro/runtime_utilities/injector:injected_key",
            "//src/java/com/claro/runtime_utilities/injector:injected_key_identifier",
            "//:apache_commons_text",
            "//:guava",
            "@jflex_rules//third_party/cup",  # the runtime would be sufficient
        ],
    )

    native.java_library(
        name = "claro_parser_exception",
        srcs = ["ClaroParserException.java"],
    )

    cup(
        name = name + "_gen_parser",
        src = "ClaroParser.cup",
        parser = "ClaroParser",
        symbols = "Tokens",
    )

    jflex(
        name = name + "_gen_lexer",
        srcs = ["ClaroLexer.flex"],
        outputs = ["ClaroLexer.java"],
    )

    native.java_library(
        name = "lexed_value",
        srcs = ["LexedValue.java"],
        deps = [
            "//:autovalue",
        ],
    )
