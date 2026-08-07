package com.auvdidao.a12teachingagent.material;

import com.auvdidao.a12teachingagent.common.exception.ConflictException;
import com.auvdidao.a12teachingagent.domain.common.GenerationMode;
import com.auvdidao.a12teachingagent.domain.common.MaterialFileType;
import com.auvdidao.a12teachingagent.domain.common.MaterialParseStatus;
import com.auvdidao.a12teachingagent.domain.common.PurposeType;
import com.auvdidao.a12teachingagent.domain.common.UploadStatus;
import com.auvdidao.a12teachingagent.domain.material.MaterialPurpose;
import com.auvdidao.a12teachingagent.domain.material.UploadedMaterial;
import com.auvdidao.a12teachingagent.domain.material.repository.MaterialPurposeRepository;
import com.auvdidao.a12teachingagent.domain.material.repository.UploadedMaterialRepository;
import com.auvdidao.a12teachingagent.domain.requirement.RequirementSummary;
import com.auvdidao.a12teachingagent.material.parse.MaterialPrototypeParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MaterialParseConcurrencyTest {

    private static final AtomicLong PROJECT_IDS = new AtomicLong(991000L);

    @Autowired
    private MaterialParseService materialParseService;

    @Autowired
    private UploadedMaterialRepository materialRepository;

    @Autowired
    private MaterialPurposeRepository purposeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @SpyBean
    private MaterialService materialService;

    @MockBean
    private MaterialPrototypeParser prototypeParser;

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void sameMaterialAllowsOnlyOneParserCallAndRejectsSecondRequest() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch parserStarted = new CountDownLatch(1);
        CountDownLatch releaseParser = new CountDownLatch(1);
        AtomicInteger parserCalls = new AtomicInteger();

        when(prototypeParser.parse(any(), anyList(), any())).thenAnswer(invocation -> {
            parserCalls.incrementAndGet();
            parserStarted.countDown();
            await(releaseParser);
            return parsedContent();
        });

        executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> materialParseService.parse(
                fixture.projectId(), fixture.materialId(), false));
        assertTrue(parserStarted.await(5, TimeUnit.SECONDS));

        Future<Throwable> second = executor.submit(() -> {
            try {
                materialParseService.parse(fixture.projectId(), fixture.materialId(), false);
                return null;
            } catch (Throwable error) {
                return error;
            }
        });

        Throwable error = second.get(5, TimeUnit.SECONDS);
        assertInstanceOf(ConflictException.class, error);
        assertEquals("Material parsing is already in progress", error.getMessage());

        releaseParser.countDown();
        assertNotNull(first.get(20, TimeUnit.SECONDS));
        assertEquals(1, parserCalls.get());
    }

    @Test
    void pessimisticWriteLockBlocksSameMaterialUntilFirstTransactionCommits() throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        CountDownLatch secondLockAcquired = new CountDownLatch(1);

        executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> inTransaction(() -> {
            findForUpdate(fixture);
            firstLockAcquired.countDown();
            await(releaseFirstLock);
        }));
        assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS));

        Future<?> second = executor.submit(() -> inTransaction(() -> {
            findForUpdate(fixture);
            secondLockAcquired.countDown();
        }));

        assertFalse(secondLockAcquired.await(500, TimeUnit.MILLISECONDS));
        releaseFirstLock.countDown();

        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        assertTrue(secondLockAcquired.getCount() == 0);
    }

    @Test
    void differentMaterialsCanAcquireLocksIndependently() throws Exception {
        Fixture firstFixture = createFixture();
        Fixture secondFixture = createFixture();
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch secondLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);

        executor = Executors.newFixedThreadPool(2);
        Future<?> first = executor.submit(() -> inTransaction(() -> {
            findForUpdate(firstFixture);
            firstLockAcquired.countDown();
            await(releaseFirstLock);
        }));
        assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS));

        Future<?> second = executor.submit(() -> inTransaction(() -> {
            findForUpdate(secondFixture);
            secondLockAcquired.countDown();
        }));

        assertTrue(secondLockAcquired.await(5, TimeUnit.SECONDS));
        releaseFirstLock.countDown();
        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
    }

    @Test
    void completedMaterialCanBeParsedAgain() {
        Fixture fixture = createFixture();
        when(prototypeParser.parse(any(), anyList(), any())).thenReturn(parsedContent());

        materialParseService.parse(fixture.projectId(), fixture.materialId(), false);
        materialParseService.parse(fixture.projectId(), fixture.materialId(), true);

        assertEquals(MaterialParseStatus.SUCCEEDED,
                materialRepository.findById(fixture.materialId()).orElseThrow().getParseStatus());
    }

    private Fixture createFixture() {
        long projectId = PROJECT_IDS.incrementAndGet();
        RequirementSummary summary = requirementSummary(projectId);
        doReturn(summary).when(materialService).requireConfirmedSummary(projectId);

        return inTransaction(() -> {
            UploadedMaterial material = new UploadedMaterial();
            material.setProjectId(projectId);
            material.setOriginalFileName("concurrency-" + projectId + ".pdf");
            material.setFileName("safe-" + projectId + ".pdf");
            material.setFilePath(projectId + "/safe-" + projectId + ".pdf");
            material.setFileExtension("pdf");
            material.setFileType(MaterialFileType.PDF);
            material.setContentType("application/pdf");
            material.setFileSize(10L);
            material.setUploadStatus(UploadStatus.UPLOADED);
            material.setParseStatus(MaterialParseStatus.NOT_STARTED);
            UploadedMaterial saved = materialRepository.saveAndFlush(material);

            MaterialPurpose purpose = new MaterialPurpose();
            purpose.setProjectId(projectId);
            purpose.setMaterialId(saved.getId());
            purpose.setPurposeType(PurposeType.TEXTBOOK_BASIS);
            purposeRepository.saveAndFlush(purpose);
            return new Fixture(projectId, saved.getId());
        });
    }

    private <T> T inTransaction(ThrowingSupplier<T> supplier) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return supplier.get();
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private void inTransaction(ThrowingRunnable runnable) {
        inTransaction(() -> {
            runnable.run();
            return null;
        });
    }

    private UploadedMaterial findForUpdate(Fixture fixture) {
        return materialRepository.findByIdAndProjectIdForUpdate(fixture.materialId(), fixture.projectId())
                .orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static MaterialPrototypeParser.ParsedContent parsedContent() {
        return new MaterialPrototypeParser.ParsedContent(
                "Photosynthesis summary",
                List.of("photosynthesis"),
                List.of("explain")
        );
    }

    private static RequirementSummary requirementSummary(long projectId) {
        RequirementSummary summary = new RequirementSummary();
        summary.setProjectId(projectId);
        summary.setGradeLevel("Grade 8");
        summary.setSubject("Biology");
        summary.setTopic("Photosynthesis");
        summary.setLessonDuration("45 minutes");
        summary.setTeachingGoals("Explain photosynthesis");
        summary.setKeyPoints("Light energy");
        summary.setDifficultPoints("Energy conversion");
        summary.setOutputTypes(List.of("PPT"));
        summary.setGenerationMode(GenerationMode.STANDARD);
        return summary;
    }

    private record Fixture(long projectId, Long materialId) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
