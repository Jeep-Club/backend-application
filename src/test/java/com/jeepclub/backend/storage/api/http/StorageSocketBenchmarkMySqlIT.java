package com.jeepclub.backend.storage.api.http;

import com.jeepclub.backend.iam.identity.api.module.UserRegistration;
import com.jeepclub.backend.iam.identity.api.module.UserRegistrationData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Diagnostic end-to-end timing; opt in because it creates a MySQL container and processes 22 images. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"storage.media.enabled=true","storage.legacy-backfill.enabled=false","server.address=127.0.0.1"})
@ActiveProfiles("test") @Testcontainers
@EnabledIfSystemProperty(named="storage.socketBenchmark",matches="true")
class StorageSocketBenchmarkMySqlIT {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.46");
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("storage-socket-benchmark-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("storage.local.root-directory",() -> ROOT.toString());
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name",() -> "com.mysql.cj.jdbc.Driver");r.add("spring.jpa.database-platform",() -> "org.hibernate.dialect.MySQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto",() -> "validate");r.add("spring.flyway.enabled",() -> true);
    }
    @LocalServerPort int port;
    @Autowired UserRegistration users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newHttpClient();
    static final Pattern MEDIA_ID=Pattern.compile("\\\"mediaId\\\"\\s*:\\s*(\\d+)");
    record Result(int status,long milliseconds,long mediaId,String error) {}

    private byte[] fixture(long seed) throws Exception {
        var image=new BufferedImage(1024,768,BufferedImage.TYPE_INT_RGB);
        var random=new Random(seed);int[] pixels=new int[1024*768];
        for(int i=0;i<pixels.length;i++) pixels[i]=random.nextInt(0x1000000);
        image.setRGB(0,0,1024,768,pixels,0,1024);
        var out=new ByteArrayOutputStream();assertThat(ImageIO.write(image,"png",out)).isTrue();return out.toByteArray();
    }
    private Result send(String token,byte[] bytes) {
        long start=System.nanoTime();
        try {
            var out=new ByteArrayOutputStream();
            out.write("--boundary123\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);out.write("\r\n--boundary123--\r\n".getBytes(StandardCharsets.US_ASCII));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/storage/media/profile"))
                .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary=boundary123")
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            long millis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            var matcher=MEDIA_ID.matcher(response.body());long id=matcher.find()?Long.parseLong(matcher.group(1)):0;
            return new Result(response.statusCode(),millis,id,"");
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(0,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),0,e.getClass().getSimpleName());
        }
    }
    @Test void concurrentCompleteUploadsOverHttp() throws Exception {
        var user=users.registerAndAuthenticate(new UserRegistrationData("Benchmark fixture",null,"benchmark-socket@example.com","52998224725",null,null,null,Instant.now()),"Password@123");
        var corpus=new ArrayList<byte[]>();for(int i=0;i<22;i++) corpus.add(fixture(4711L+i));
        var results=new ArrayList<Result>();
        for(int i=0;i<2;i++) {var warmup=send(user.accessToken(),corpus.get(i));assertThat(warmup.status()).isEqualTo(201);assertThat(warmup.mediaId()).isPositive();}
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var jobs=new ArrayList<java.util.concurrent.Future<Result>>();
            for(int i=2;i<corpus.size();i++) {byte[] bytes=corpus.get(i);jobs.add(executor.submit(() -> {start.await();return send(user.accessToken(),bytes);}));}
            start.countDown();for(var job:jobs) results.add(job.get(120,TimeUnit.SECONDS));
        } finally {start.countDown();executor.shutdownNow();}
        var times=results.stream().map(Result::milliseconds).sorted().toList();
        long p50=times.get((int)Math.ceil(times.size()*0.50)-1),p95=times.get((int)Math.ceil(times.size()*0.95)-1);
        long minBytes=corpus.stream().mapToLong(b -> b.length).min().orElseThrow(),maxBytes=corpus.stream().mapToLong(b -> b.length).max().orElseThrow();
        var statuses=results.stream().collect(java.util.stream.Collectors.groupingBy(Result::status,java.util.TreeMap::new,java.util.stream.Collectors.counting()));
        System.out.println("STORAGE_SOCKET_BENCHMARK samples="+results.size()+" warmup=2 concurrency=2 image=1024x768-random-png input_bytes_min="+minBytes+" input_bytes_max="+maxBytes
            +" mysql=8.0.46 p50_ms="+p50+" p95_ms="+p95+" max_ms="+times.get(times.size()-1)+" statuses="+statuses
            +" errors="+results.stream().filter(r -> !r.error().isEmpty()).map(Result::error).toList()+" os="+System.getProperty("os.name")+" java="+System.getProperty("java.version")+" cpus="+Runtime.getRuntime().availableProcessors());
        System.out.println("STORAGE_SOCKET_BUDGETS "+jdbc.queryForList("select scope_id,used_bytes,in_flight,window_attempts,charged_uploads from storage_upload_budgets order by scope_id")
            +" permits="+jdbc.queryForList("select completed,charged,count(*) as quantity from storage_upload_permits group by completed,charged"));
        assertThat(results).allSatisfy(r -> {assertThat(r.status()).isEqualTo(201);assertThat(r.mediaId()).isPositive();});
        assertThat(new HashSet<>(results.stream().map(Result::mediaId).toList())).hasSize(20);
        assertThat(jdbc.queryForObject("select in_flight from storage_upload_budgets where scope_id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select charged_uploads from storage_upload_budgets where scope_id=1",Long.class)).isEqualTo(22);
        assertThat(jdbc.queryForObject("select used_bytes from storage_upload_budgets where scope_id=1",Long.class))
            .isEqualTo(jdbc.queryForObject("select coalesce(sum(accounted_bytes),0) from storage_upload_permits where actor_user_id=1",Long.class));
        for(var result:results) {
            var rows=jdbc.queryForList("select v.variant_code,b.sha256,b.size_bytes,cast(b.ready as unsigned) as ready from storage_media_variants v join storage_variant_blobs vb on vb.variant_id=v.id join storage_blobs b on b.sha256=vb.sha256 where v.media_id=?",result.mediaId());
            assertThat(rows).hasSize(3);
            assertThat(rows.stream().map(row -> row.get("variant_code").toString()).toList()).containsExactlyInAnyOrder("MASTER","AVATAR_SMALL","PROFILE_PREVIEW");
            for(var row:rows) {
                assertThat(((Number)row.get("ready")).intValue()).isEqualTo(1);
                String digest=row.get("sha256").toString();byte[] actual=Files.readAllBytes(ROOT.resolve("storage-blobs").resolve(digest+".bin"));
                assertThat(actual.length).isEqualTo(((Number)row.get("size_bytes")).longValue());
                assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual))).isEqualTo(digest);
            }
        }
        assertThat(p95).isLessThan(10_000);
    }
}

