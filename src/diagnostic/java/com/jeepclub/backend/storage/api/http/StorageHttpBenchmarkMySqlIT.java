package com.jeepclub.backend.storage.api.http;

import com.jeepclub.backend.iam.identity.api.module.*;
import com.jeepclub.backend.storage.core.repository.MediaGovernance;
import com.jeepclub.backend.storage.core.domain.model.ModerationDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.jayway.jsonpath.JsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StorageHttpBenchmarkMySqlIT extends StorageHttpFlowTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="storage.benchmark",matches="true")
    void benchmarkConcurrentProfileUploads() throws Exception {
        var user=users.registerAndAuthenticate(new UserRegistrationData("Benchmark fixture",null,"benchmark-storage@example.com","52998224725",null,null,null,Instant.now()),"Password@123");
        var corpus=new java.util.ArrayList<byte[]>();
        for(int i=0;i<22;i++) {
            var image=new BufferedImage(1024,768,BufferedImage.TYPE_INT_RGB);
            var random=new java.util.Random(4711L+i);
            int[] pixels=new int[1024*768];for(int p=0;p<pixels.length;p++) pixels[p]=random.nextInt(0x1000000);
            image.setRGB(0,0,1024,768,pixels,0,1024);
            var encoded=new ByteArrayOutputStream();ImageIO.write(image,"png",encoded);corpus.add(encoded.toByteArray());
        }
        for(int i=0;i<2;i++) {
            mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","fixture.png","image/png",corpus.get(i)))
                .header("Authorization","Bearer "+user.accessToken())).andExpect(status().isCreated());
        }
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        var futures=new java.util.ArrayList<java.util.concurrent.Future<Long>>();
        try {
            for(int i=2;i<corpus.size();i++) {
                byte[] fixture=corpus.get(i);
                futures.add(pool.submit(() -> {
                    start.await();long begin=System.nanoTime();
                    mvc.perform(multipart("/storage/media/profile").file(new MockMultipartFile("file","fixture.png","image/png",fixture))
                        .header("Authorization","Bearer "+user.accessToken())).andExpect(status().isCreated());
                    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
                }));
            }
            start.countDown();var durations=new java.util.ArrayList<Long>();
            for(var future:futures) durations.add(future.get(120,java.util.concurrent.TimeUnit.SECONDS));
            java.util.Collections.sort(durations);long p95=durations.get((int)Math.ceil(durations.size()*.95)-1);
            System.out.println("STORAGE_BENCHMARK samples="+durations.size()+" concurrency=2 warmup=2 input=1024x768-random-png bytes="+corpus.get(0).length
                    +" p95_ms="+p95+" max_ms="+durations.get(durations.size()-1)+" os="+System.getProperty("os.name")
                    +" java="+System.getProperty("java.version")+" cpus="+Runtime.getRuntime().availableProcessors());
            org.assertj.core.api.Assertions.assertThat(p95).isLessThan(10_000);
        } finally {pool.shutdownNow();}
    }
}
