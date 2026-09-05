package top.focess.veto.agent.capability;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.WorkspaceReadTool;
import top.focess.veto.agent.tool.WorkspaceWriteTool;

/** Build-time enforcement for capability-bound native tools. */
@AnalyzeClasses(packages = "top.focess.veto")
class CapabilityBoundaryTest {

    @ArchTest
    static final ArchRule bundledToolsCannotOpenHostFilesystems =
            noClasses()
                    .that()
                    .resideInAPackage("top.focess.veto.agent.tool.builtin..")
                    .and()
                    .haveSimpleNameNotEndingWith("Test")
                    .should()
                    .callMethodWhere(
                            DescribedPredicate.<JavaMethodCall>describe(
                                    "open a filesystem or resolve a host file directly",
                                    call -> {
                                        String owner = call.getTargetOwner().getName();
                                        String method = call.getName();
                                        return (owner.equals("java.nio.file.FileSystems")
                                                        && method.equals("newFileSystem"))
                                                || (owner.equals("java.nio.file.Path")
                                                        && (method.equals("toFile")
                                                                || method.equals("toRealPath")));
                                    }));

    @ArchTest
    static final ArchRule bundledToolPackageCannotHideFilesystemAccessInHelpers =
            noClasses()
                    .that()
                    .resideInAPackage("top.focess.veto.agent.tool.builtin..")
                    .and()
                    .haveSimpleNameNotEndingWith("Test")
                    .should()
                    .dependOnClassesThat()
                    .haveNameMatching(
                            "java\\.io\\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)|java\\.nio\\.file\\.Files|java\\.nio\\.file\\.spi\\..*|java\\.nio\\.channels\\.(FileChannel|AsynchronousFileChannel)");

    @ArchTest
    static final ArchRule workspaceReadToolsCannotBypassTheirCapability =
            noClasses()
                    .that()
                    .areAssignableTo(ToolDocs.nonNullClass(WorkspaceReadTool.class))
                    .and()
                    .areNotInterfaces()
                    .should()
                    .dependOnClassesThat()
                    .haveNameMatching(
                            "java\\.io\\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)|java\\.nio\\.file\\.Files|java\\.nio\\.file\\.spi\\..*|java\\.nio\\.channels\\.(FileChannel|AsynchronousFileChannel)");

    @ArchTest
    static final ArchRule workspaceWriteToolsCannotBypassTheirCapability =
            noClasses()
                    .that()
                    .areAssignableTo(ToolDocs.nonNullClass(WorkspaceWriteTool.class))
                    .and()
                    .areNotInterfaces()
                    .should()
                    .dependOnClassesThat()
                    .haveNameMatching(
                            "java\\.io\\.(File|FileInputStream|FileOutputStream|FileReader|FileWriter|RandomAccessFile)|java\\.nio\\.file\\.Files|java\\.nio\\.file\\.spi\\..*|java\\.nio\\.channels\\.(FileChannel|AsynchronousFileChannel)");
}
