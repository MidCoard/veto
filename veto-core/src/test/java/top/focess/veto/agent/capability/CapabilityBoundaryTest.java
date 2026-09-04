package top.focess.veto.agent.capability;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.WorkspaceReadTool;
import top.focess.veto.agent.mcp.WorkspaceWriteTool;

/** Build-time enforcement for capability-bound native tools. */
@AnalyzeClasses(packages = "top.focess.veto")
class CapabilityBoundaryTest {

    @ArchTest
    static final ArchRule bundledToolPackageCannotHideFilesystemAccessInHelpers =
            noClasses()
                    .that()
                    .resideInAPackage("top.focess.veto.agent.mcp.tools..")
                    .and()
                    .haveSimpleNameNotEndingWith("Test")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("java.nio.file..", "java.io..");

    @ArchTest
    static final ArchRule workspaceReadToolsCannotBypassTheirCapability =
            noClasses()
                    .that()
                    .areAssignableTo(ToolDocs.nonNullClass(WorkspaceReadTool.class))
                    .and()
                    .areNotInterfaces()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("java.nio.file..", "java.io..");

    @ArchTest
    static final ArchRule workspaceWriteToolsCannotBypassTheirCapability =
            noClasses()
                    .that()
                    .areAssignableTo(ToolDocs.nonNullClass(WorkspaceWriteTool.class))
                    .and()
                    .areNotInterfaces()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("java.nio.file..", "java.io..");
}
