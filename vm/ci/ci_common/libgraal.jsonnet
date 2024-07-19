local vm = import '../ci_includes/vm.jsonnet';
local graal_common = import '../../../ci/ci_common/common.jsonnet';
local repo_config = import '../../../ci/repo-configuration.libsonnet';
local devkits = graal_common.devkits;
local c = import 'common.jsonnet';
local g = vm.compiler_gate;
local utils = import '../../../ci/ci_common/common-utils.libsonnet';
local galahad = import '../../../ci/ci_common/galahad-common.libsonnet';

{
  local underscore(s) = std.strReplace(s, "-", "_"),
  local os(os_arch) = std.split(os_arch, "-")[0],
  local arch(os_arch) = std.split(os_arch, "-")[1],
  local t(limit) = {timelimit: limit},

  libgraal_build(build_args):: {
    local usePGO = std.length(std.find('-Ob', build_args)) == 0,
    local ee_build_version = if usePGO == false then 'build' else 'build-libgraal-pgo',
    local build_command = if repo_config.graalvm_edition == 'ce' then 'build' else ee_build_version,
    run+: [
      ['mx', '--env', vm.libgraal_env] + ['--extra-image-builder-argument=%s' % arg for arg in build_args] + [build_command]
    ]
  },

  # enable asserts in the JVM building the image and enable asserts in the resulting native image
  libgraal_compiler_base(quickbuild_args=[], extra_vm_args=[]):: self.libgraal_build(['-J-esa', '-J-ea', '-esa', '-ea'] + quickbuild_args) + {
    run+: [
      ['mx', '--env', vm.libgraal_env, 'gate', '--task', 'LibGraal Compiler', '--extra-vm-argument=' + std.join(" ", extra_vm_args)],
    ],
    logs+: [
      '*/graal-compiler.log',
      '*/graal-compiler-ctw.log'
    ],
    timelimit: '1:00:00',
  },

  # enable asserts in the JVM building the image and enable asserts in the resulting native image
  libgraal_compiler:: self.libgraal_compiler_base() {
    # Tests that dropping libgraal into OracleJDK works (see mx_vm_gate.py)
    downloads +: if utils.contains(self.name, 'labsjdk-21') then {"ORACLEJDK_JAVA_HOME" : graal_common.jdks_data["oraclejdk21"]} else {}
  },
  libgraal_compiler_zgc:: self.libgraal_compiler_base(extra_vm_args=['-XX:+UseZGC', '-XX:-ZGenerational']),
  # enable economy mode building with the -Ob flag
  libgraal_compiler_quickbuild:: self.libgraal_compiler_base(quickbuild_args=['-Ob']) + {
    environment+: {
      # Exercise support for preventing build paths being embedded in libgraal.
      ALLOW_ABSOLUTE_PATHS_IN_OUTPUT: 'false'
    }
  },

  libgraal_truffle_base(quickbuild_args=[], extra_vm_args=[], coverage=false): self.libgraal_build(['-J-esa', '-J-ea', '-esa', '-ea'] + quickbuild_args) + {
    environment+: {
      # The Truffle TCK tests run as a part of Truffle TCK gate, tools tests run as a part of tools gate
      TEST_LIBGRAAL_EXCLUDE: 'com.oracle.truffle.tck.tests.* com.oracle.truffle.tools.* com.oracle.truffle.regex.*'
    },
    run+: [
      ['mx', '--env', vm.libgraal_env, 'gate', '--task', 'LibGraal Truffle'] + if coverage then g.jacoco_gate_args else [] +
        ['--extra-vm-argument=' + std.join(" ", ['-DGCUtils.saveHeapDumpTo=.'] + extra_vm_args)],
    ],
    logs+: [
      '*/graal-compiler.log',
      '*/graal-compiler-ctw.log',
      '*/gcutils_heapdump_*.hprof.gz'
    ],
    components+: ["truffle"],
    timelimit: '1:00:00',
    teardown+: if coverage then [
      g.upload_coverage
    ] else []
  },

  # -ea assertions are enough to keep execution time reasonable
  libgraal_truffle: self.libgraal_truffle_base(),
  libgraal_truffle_zgc: self.libgraal_truffle_base(extra_vm_args=['-XX:+UseZGC', '-XX:-ZGenerational']),
  # enable economy mode building with the -Ob flag
  libgraal_truffle_quickbuild: self.libgraal_truffle_base(['-Ob']),

  # Use economy mode for coverage testing
  libgraal_truffle_coverage: self.libgraal_truffle_base(['-Ob'], coverage=true),

  # Gate for guestgraal
  guestgraal_compiler:: {
    local guestgraal_env = std.strReplace(vm.libgraal_env, "libgraal", "guestgraal"),
    # LibGraal gate tasks currently expected to work
    local tasks = [
      "LibGraal Compiler:Basic",
      "LibGraal Compiler:FatalErrorHandling",
      "LibGraal Compiler:OOMEDumping",
      "LibGraal Compiler:SystemicFailureDetection",
      "LibGraal Compiler:CompilationTimeout:JIT",
      "LibGraal Compiler:CTW",
      "LibGraal Compiler:DaCapo",
      "LibGraal Compiler:ScalaDaCapo"
    ] +
    # Renaissance is missing the msvc redistributable on Windows [GR-50132]
    if self.os == "windows" then [] else ["LibGraal Compiler:Renaissance"],

    run+: [
      ['mx', '--env', guestgraal_env, 'build'],
      ['mx', '--env', guestgraal_env, 'native-image', '-J-esa', '-J-ea', '-esa', '-ea',
       '-p', ['mx', '--env', guestgraal_env, '--quiet', 'path', 'JNIUTILS'],
       '-cp', ['mx', '--env', guestgraal_env, '--quiet', 'path', 'GUESTGRAAL_LIBRARY'],
       '-H:+UnlockExperimentalVMOptions', '-H:+VerifyGraalGraphs', '-H:+VerifyPhases'],
      ['mx', '--env', guestgraal_env, 'gate', '--task', std.join(",", tasks), '--extra-vm-argument=-XX:JVMCILibPath=$PWD/' + vm.vm_dir],
    ],
    logs+: [
      '*/graal-compiler.log',
      '*/graal-compiler-ctw.log'
    ],
    timelimit: '1:00:00',
  },

  # See definition of `gates` local variable in ../../compiler/ci_common/gate.jsonnet
  local gate_jobs = {
    "gate-vm-libgraal_compiler-labsjdk-latest-linux-amd64": {},
    "gate-vm-libgraal_truffle-labsjdk-latest-linux-amd64": {} + galahad.exclude,
    "gate-vm-libgraal_compiler_zgc-labsjdk-latest-linux-amd64": {},
    "gate-vm-libgraal_compiler_quickbuild-labsjdk-latest-linux-amd64": {},

    "gate-vm-libgraal_compiler-labsjdk-21-linux-amd64": {},
    "gate-vm-libgraal_truffle-labsjdk-21-linux-amd64": {},
  } + if repo_config.graalvm_edition == "ce" then
  {
    # GuestGraal on EE is still under construction
    "gate-vm-guestgraal_compiler-labsjdk-latest-linux-amd64": {}
  } else {},

  local gates = g.as_gates(gate_jobs),

  # See definition of `dailies` local variable in ../../compiler/ci_common/gate.jsonnet
  local dailies = {
    "daily-vm-libgraal_truffle_zgc-labsjdk-latest-linux-amd64": {},

    "daily-vm-libgraal_compiler_zgc-labsjdk-21-linux-amd64": {},
    "daily-vm-libgraal_compiler_quickbuild-labsjdk-21-linux-amd64": {},
    "daily-vm-libgraal_truffle_quickbuild-labsjdk-latest-linux-amd64": t("1:10:00"),
    "daily-vm-libgraal_truffle_quickbuild-labsjdk-21-linux-amd64": t("1:10:00"),
  } + g.as_dailies(gate_jobs),

  # See definition of `weeklies` local variable in ../../compiler/ci_common/gate.jsonnet
  local weeklies = {
    "weekly-vm-libgraal_truffle_coverage*": {}
  },

  # See definition of `monthlies` local variable in ../../compiler/ci_common/gate.jsonnet
  local monthlies = {},

  local svm_common(os_arch, jdk) =
    if (os_arch == 'windows-amd64') then
      c.svm_common_windows_amd64(jdk)
    else
      c.svm_common,

  local all_os_arches = [
    "linux-amd64",
    "linux-aarch64",
    "darwin-amd64",
    "darwin-aarch64",
    "windows-amd64"
  ],

  # Builds run on all platforms (platform = JDK + OS + ARCH)
  local all_platforms_builds = [
    c.vm_base(os(os_arch), arch(os_arch), 'gate') +
    svm_common(os_arch, jdk) +
    vm.custom_vm +
    g.make_build(jdk, os_arch, task, extra_tasks=self, suite="vm",
                 include_common_os_arch=false,
                 jdk_name = "labsjdk",
                 gates_manifest=gates,
                 dailies_manifest=dailies,
                 weeklies_manifest=weeklies,
                 monthlies_manifest=monthlies).build +
    vm["vm_java_" + jdk]
    for jdk in [
      "21",
      "Latest"
    ]
    for os_arch in all_os_arches
    for task in [
      "libgraal_compiler",
      "libgraal_truffle",
      "libgraal_compiler_quickbuild",
      "libgraal_truffle_quickbuild"
    ] +
    # GuestGraal on EE is still under construction
    (if repo_config.graalvm_edition == "ce" then ["guestgraal_compiler"] else [])
  ],

  local adjust_windows_version(gate) = (
      # replace 2016 with 2019
     gate + { capabilities: [ if x == "windows_server_2016" then "windows_server_2019" else x for x in gate.capabilities ] }
  ),

  # Builds run on all platforms (platform = JDK + OS + ARCH) but Windows currently requires Windows server 2019
  local all_platforms_zgc_builds = [
    adjust_windows_version(c.vm_base(os(os_arch), arch(os_arch), 'gate')) +
    svm_common(os_arch, jdk) +
    vm.custom_vm +
    g.make_build(jdk, os_arch, task, extra_tasks=self, suite="vm",
                 include_common_os_arch=false,
                 gates_manifest=gates,
                 dailies_manifest=dailies,
                 weeklies_manifest=weeklies,
                 monthlies_manifest=monthlies).build +
    vm["vm_java_" + jdk]
    for jdk in [
      "Latest",
    ]
    for os_arch in all_os_arches
    for task in [
      "libgraal_compiler_zgc",
      "libgraal_truffle_zgc",
    ]
  ],

  # Coverage builds only on jdk21 (GR-46676)
  local coverage_jdk21_builds = [
    c.vm_base(os(os_arch), arch(os_arch), 'gate') +
    svm_common(os_arch, jdk) +
    vm.custom_vm +
    g.make_build(jdk, os_arch, task, extra_tasks=self, suite="vm",
                 include_common_os_arch=false,
                 gates_manifest=gates,
                 dailies_manifest=dailies,
                 weeklies_manifest=weeklies,
                 monthlies_manifest=monthlies).build +
    vm["vm_java_" + jdk]
    for jdk in [
      "21"
    ]
    for os_arch in [
      "linux-amd64",
      "darwin-aarch64",
      "windows-amd64"
    ]
    for task in [
      "libgraal_truffle_coverage"
    ]
  ],

  # Complete set of builds defined in this file
  local all_builds =
    all_platforms_builds +
    all_platforms_zgc_builds +
    coverage_jdk21_builds,

  builds: if
      g.check_manifest(gates, all_builds, std.thisFile, "gates").result
    then
      local conf = repo_config.vm.libgraal_predicate_conf;
      [utils.add_gate_predicate(b, suites=conf.suites, extra_excludes=conf.extra_excludes) for b in all_builds]
}
