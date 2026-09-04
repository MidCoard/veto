package top.focess.veto.training;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.controller.RequestAuthorization;
import top.focess.veto.vault.UserContext;

class TrainingControllerAuthorizationTest {

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void everyEndpointRejectsUnauthenticatedRequests() {
        TrainingController controller = controller(new RequestAuthorization(name -> true));

        assertUnauthorized(() -> controller.startTraining(null));
        assertUnauthorized(controller::cancelTraining);
        assertUnauthorized(controller::getProgress);
        assertUnauthorized(() -> controller.deployModel(new DeployModelRequest(null)));
        assertUnauthorized(controller::getStatus);
        assertUnauthorized(controller::runQualityCheck);
        assertUnauthorized(controller::getEvaluation);
    }

    @Test
    void everyEndpointRejectsNonAdminUsers() {
        UserContext.set("member");
        TrainingController controller = controller(new RequestAuthorization(name -> false));

        assertForbidden(() -> controller.startTraining(null));
        assertForbidden(controller::cancelTraining);
        assertForbidden(controller::getProgress);
        assertForbidden(() -> controller.deployModel(new DeployModelRequest(null)));
        assertForbidden(controller::getStatus);
        assertForbidden(controller::runQualityCheck);
        assertForbidden(controller::getEvaluation);
    }

    @Test
    void adminCanReadTrainingState() {
        UserContext.set("admin");
        TrainingController controller = controller(new RequestAuthorization(name -> true));

        assertDoesNotThrow(controller::getProgress);
        assertDoesNotThrow(controller::getStatus);
        assertDoesNotThrow(controller::getEvaluation);
    }

    @Test
    void manualDeployDefaultsToTheLatestConversion(@TempDir @NonNull Path root) throws Exception {
        UserContext.set("admin");
        TrainingConfiguration config = new TrainingConfiguration();
        config.setModelOutputDir(root.toString());
        config.setAutoDeployOnCompletion(false);
        config.setRestartBridgeOnDeploy(false);
        TrainingManager manager = new TrainingManager(config, new ObjectMapper());
        Path target = Files.write(root.resolve(config.getDefaultGgufName()), new byte[] {9});
        Path converted =
                Files.write(
                        Files.createDirectories(root.resolve("conversion/gguf"))
                                .resolve("veto-slm-q4_k_m.gguf"),
                        new byte[] {1, 2});
        manager.completeTraining(converted);
        TrainingController controller =
                new TrainingController(manager, config, new RequestAuthorization(name -> true));
        assertEquals(
                HttpStatus.OK,
                controller.deployModel(new DeployModelRequest(null)).getStatusCode());
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(target));
    }

    private static @NonNull TrainingController controller(
            @NonNull RequestAuthorization authorization) {
        TrainingConfiguration config = new TrainingConfiguration();
        config.setTrainingDir("./nonexistent-for-authorization-test");
        return new TrainingController(
                new TrainingManager(config, new ObjectMapper()), config, authorization);
    }

    private static void assertUnauthorized(@NonNull Runnable request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, request::run);
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    private static void assertForbidden(@NonNull Runnable request) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, request::run);
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }
}
