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

/** Opt-in end-to-end timing with a MySQL container and optional public gallery fixtures. */
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
    record Sample(String format, int width, int height, byte[] bytes) {}

    private Sample fixture(int index) throws Exception {
        int kind = index % 5;
        String format = switch (kind) {
            case 0, 1, 4 -> "png";
            case 2 -> "jpeg";
            default -> "webp";
        };
        int width = switch (kind) {
            case 0 -> 1024;
            case 1 -> 800;
            case 2 -> 3200;
            case 4 -> 5000;
            default -> 1200;
        };
        int height = switch (kind) {
            case 0 -> 768;
            case 1 -> 1200;
            case 2 -> 2400;
            case 4 -> 4000;
            default -> 1200;
        };
        boolean alpha = kind == 1;
        var image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var random = new Random(4711L + index);
        int[] pixels = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[x] = kind == 4 ? ((x * 255 / width) << 16) | ((y * 255 / height) << 8) | index
                        : random.nextInt();
            }
            image.setRGB(0, y, width, 1, pixels, 0, width);
        }
        if (format.equals("webp")) {
            return new Sample(format, width, height, dev.matrixlab.webp4j.WebPCodec.encodeImage(image, 85f, false));
        }
        var out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, out)).isTrue();
        return new Sample(format, width, height, out.toByteArray());
    }
    private Result send(String token, Sample sample) {
        long start=System.nanoTime();
        try {
            var out=new ByteArrayOutputStream();
            String part = "--boundary123\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture."
                    + sample.format() + "\"\r\nContent-Type: image/" + sample.format() + "\r\n\r\n";
            out.write(part.getBytes(StandardCharsets.US_ASCII));
            out.write(sample.bytes());out.write("\r\n--boundary123--\r\n".getBytes(StandardCharsets.US_ASCII));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/storage/media/profile"))
                .timeout(java.time.Duration.ofSeconds(30))
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
        var corpus=new ArrayList<Sample>();for(int i=0;i<22;i++) corpus.add(fixture(i));
        String publicGallery = System.getProperty("storage.benchmark.publicGallery");
        if (publicGallery != null) {
            for (String name : java.util.List.of("experiencia-trilha.jpg", "experiencia-serra.jpg", "experiencia-lama.jpg")) {
                Path file = Path.of(publicGallery).resolve(name);
                var image = ImageIO.read(file.toFile());
                assertThat(image).isNotNull();
                corpus.add(new Sample("jpeg", image.getWidth(), image.getHeight(), Files.readAllBytes(file)));
            }
        }
        var results=new ArrayList<Result>();
        for(int i=0;i<2;i++) {var warmup=send(user.accessToken(),corpus.get(i));assertThat(warmup.status()).isEqualTo(201);assertThat(warmup.mediaId()).isPositive();}
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            var jobs=new ArrayList<java.util.concurrent.Future<Result>>();
            for(int i=2;i<corpus.size();i++) {Sample sample=corpus.get(i);jobs.add(executor.submit(() -> {start.await();return send(user.accessToken(),sample);}));}
            start.countDown();for(var job:jobs) results.add(job.get(120,TimeUnit.SECONDS));
        } finally {start.countDown();executor.shutdownNow();}
        var times=results.stream().map(Result::milliseconds).sorted().toList();
        long p50=times.get((int)Math.ceil(times.size()*0.50)-1),p95=times.get((int)Math.ceil(times.size()*0.95)-1);
        var measured = corpus.subList(2, corpus.size());
        long minBytes=measured.stream().mapToLong(b -> b.bytes().length).min().orElseThrow(),maxBytes=measured.stream().mapToLong(b -> b.bytes().length).max().orElseThrow();
        var formats = measured.stream().map(s -> s.format() + "-" + s.width() + "x" + s.height()).distinct().toList();
        var statuses=results.stream().collect(java.util.stream.Collectors.groupingBy(Result::status,java.util.TreeMap::new,java.util.stream.Collectors.counting()));
        System.out.println("STORAGE_SOCKET_BENCHMARK samples="+results.size()+" warmup=2 concurrency=2 images="+formats
            +" public_gallery_samples="+(corpus.size()-22)+" input_bytes_min="+minBytes+" input_bytes_max="+maxBytes
            +" mysql=8.0.46 p50_ms="+p50+" p95_ms="+p95+" max_ms="+times.get(times.size()-1)+" statuses="+statuses
            +" errors="+results.stream().filter(r -> !r.error().isEmpty()).map(Result::error).toList()+" os="+System.getProperty("os.name")+" java="+System.getProperty("java.version")+" cpus="+Runtime.getRuntime().availableProcessors());
        System.out.println("STORAGE_SOCKET_BUDGETS "+jdbc.queryForList("select scope_id,used_bytes,in_flight,window_attempts,charged_uploads from storage_upload_budgets order by scope_id")
            +" permits="+jdbc.queryForList("select completed,charged,count(*) as quantity from storage_upload_permits group by completed,charged"));
        assertThat(results).allSatisfy(r -> {assertThat(r.status()).isEqualTo(201);assertThat(r.mediaId()).isPositive();});
        assertThat(new HashSet<>(results.stream().map(Result::mediaId).toList())).hasSize(measured.size());
        assertThat(jdbc.queryForObject("select in_flight from storage_upload_budgets where scope_id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select charged_uploads from storage_upload_budgets where scope_id=1",Long.class)).isEqualTo((long) corpus.size());
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
        System.out.println("STORAGE_SOCKET_VARIANTS "+jdbc.queryForList(
                "select variant_code,min(width) as width_min,max(width) as width_max,"
                +"min(height) as height_min,max(height) as height_max,min(size_bytes) as bytes_min,"
                +"max(size_bytes) as bytes_max from storage_media_variants group by variant_code order by variant_code"));
        assertThat(p95).isLessThan(10_000);
    }
}
