package com.jeepclub.backend.storage.api.http;

import com.jeepclub.backend.iam.identity.api.module.UserRegistration;
import com.jeepclub.backend.iam.identity.api.module.UserRegistrationData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"storage.media.enabled=true","server.address=127.0.0.1"})
@ActiveProfiles("test") @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StorageMultipartSocketTest {
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("storage-multipart-test-");} catch(Exception e) {throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {registry.add("storage.local.root-directory",() -> ROOT.toString());}
    @LocalServerPort int port;
    @Autowired UserRegistration users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private long physicalFiles() throws Exception {
        try(var files=Files.list(ROOT.resolve("storage-blobs"))) {return files.count();}
    }

    static byte[] paddedPng() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB),"png",out);
        byte[] png=out.toByteArray();int n=10*1024*1024-png.length-12;byte[] padding=new byte[n];byte[] type={'n','p','T','c'};
        var crc=new java.util.zip.CRC32();crc.update(type);crc.update(padding);
        var result=new ByteArrayOutputStream();var data=new java.io.DataOutputStream(result);
        data.write(png,0,png.length-12);data.writeInt(n);data.write(type);data.write(padding);data.writeInt((int)crc.getValue());data.write(png,png.length-12,12);
        return result.toByteArray();
    }
    private HttpResponse<String> upload(String token,byte[] image) throws Exception {
        return upload(token,image,"image/png");
    }
    private HttpResponse<String> upload(String token,byte[] image,String mime) throws Exception {
        var out=new ByteArrayOutputStream();out.write(("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(image);out.write("\r\n--boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/storage/media/profile"))
            .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary=boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> oversizedRequest(String token) throws Exception {
        var out=new ByteArrayOutputStream();byte[] part=new byte[7*1024*1024+512*1024];
        for(int i=0;i<4;i++) {
            out.write(("--boundary\r\nContent-Disposition: form-data; name=\"extra"+i+"\"; filename=\"x.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(part);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write("--boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/storage/media/profile"))
            .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary=boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString());
    }
    private String uploadCompletelyBeforeReading(String token,byte[] image) throws Exception {
        var body=new ByteArrayOutputStream();body.write("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        body.write(image);body.write("\r\n--boundary--\r\n".getBytes(StandardCharsets.US_ASCII));
        try(var socket=new java.net.Socket("127.0.0.1",port)) {
            socket.setSoTimeout(30000);var output=socket.getOutputStream();
            output.write(("POST /storage/media/profile HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nAuthorization: Bearer "+token+"\r\nContent-Type: multipart/form-data; boundary=boundary\r\nContent-Length: "+body.size()+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            body.writeTo(output);output.flush();socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void servletAcceptsTenMibAndRejectsOneExtraByteWithoutPersisting() throws Exception {
        var user=users.registerAndAuthenticate(new UserRegistrationData("Multipart fixture",null,"multipart-storage@example.com","11144477735",null,null,null,Instant.now()),"Password@123");
        byte[] exact=paddedPng();assertThat(exact).hasSize(10*1024*1024);
        var accepted=upload(user.accessToken(),exact);assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        long assets=jdbc.queryForObject("select count(*) from storage_media_assets",Long.class);
        long variants=jdbc.queryForObject("select count(*) from storage_media_variants",Long.class);
        long blobs=jdbc.queryForObject("select count(*) from storage_blobs",Long.class);
        long physical=physicalFiles();
        long permits=jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class);
        var pngBytes=new ByteArrayOutputStream();var jpegBytes=new ByteArrayOutputStream();var img=new BufferedImage(16,12,BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img,"png",pngBytes);ImageIO.write(img,"jpeg",jpegBytes);
        byte[] png=pngBytes.toByteArray(),jpeg=jpegBytes.toByteArray(),webp=dev.matrixlab.webp4j.WebPCodec.encodeLosslessImage(img);
        byte[] corruptPng=png.clone(),corruptJpeg=jpeg.clone(),corruptWebp=webp.clone();corruptPng[29]^=1;corruptJpeg[2]=0;corruptWebp[4]^=1;
        byte[][] invalidImages={png,new byte[0],new byte[]{1,2,3},corruptPng,corruptJpeg,corruptWebp,java.util.Arrays.copyOf(png,png.length-1),java.util.Arrays.copyOf(jpeg,jpeg.length-1),java.util.Arrays.copyOf(webp,webp.length-1)};
        String[] invalidTypes={"image/jpeg","image/png","image/png","image/png","image/jpeg","image/webp","image/png","image/jpeg","image/webp"};
        for(int i=0;i<invalidImages.length;i++) {
            var bad=upload(user.accessToken(),invalidImages[i],invalidTypes[i]);
            assertThat(bad.statusCode()).as(bad.body()).isEqualTo(400);
            assertThat(bad.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(com.jayway.jsonpath.JsonPath.<String>read(bad.body(),"$.code")).isEqualTo("INVALID_MEDIA_REQUEST");
            assertThat((Object)com.jayway.jsonpath.JsonPath.read(bad.body(),"$.timestamp")).isNotNull();
            assertThat(bad.body()).doesNotContain("storageKey","sha256","Exception","private-images");
            assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assets);
            assertThat(jdbc.queryForObject("select count(*) from storage_media_variants",Long.class)).isEqualTo(variants);
            assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobs);
            assertThat(physicalFiles()).isEqualTo(physical);
            assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits where completed=false",Long.class)).isZero();
        }
        permits=jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class);
        var unauthorized=upload("invalid-token",png);
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        assertThat(unauthorized.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat((Object)com.jayway.jsonpath.JsonPath.read(unauthorized.body(),"$.code")).isNotNull();
        assertThat((Object)com.jayway.jsonpath.JsonPath.read(unauthorized.body(),"$.timestamp")).isNotNull();
        assertThat(unauthorized.body()).doesNotContain("storageKey","sha256","Exception","private-images");
        assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assets);
        assertThat(jdbc.queryForObject("select count(*) from storage_media_variants",Long.class)).isEqualTo(variants);
        assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobs);
        assertThat(physicalFiles()).isEqualTo(physical);
        assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class)).isEqualTo(permits);
        String complete=uploadCompletelyBeforeReading(user.accessToken(),java.util.Arrays.copyOf(exact,exact.length+1));
        assertThat(complete).startsWith("HTTP/1.1 413").containsIgnoringCase("application/problem+json").contains("STORAGE_UPLOAD_TOO_LARGE", "timestamp");
        assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assets);
        assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobs);
        assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class)).isEqualTo(permits);
        var rejected=upload(user.accessToken(),java.util.Arrays.copyOf(exact,exact.length+1));
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(413);
        assertThat(rejected.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(rejected.body(),"$.code")).isEqualTo("STORAGE_UPLOAD_TOO_LARGE");
        assertThat((Object)com.jayway.jsonpath.JsonPath.read(rejected.body(),"$.timestamp")).isNotNull();
        assertThat(rejected.body()).doesNotContain("storageKey","sha256","Exception","fixture","private-images");
        assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assets);
        assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobs);
        assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class)).isEqualTo(permits);
        var invalid=upload(user.accessToken(),new byte[]{1,2,3});
        assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(400);
        assertThat(invalid.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat(invalid.body()).doesNotContain("storageKey","sha256","Exception","private-images");
        assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assets);
        assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobs);
        assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits where completed=false",Long.class)).isZero();
        long afterInvalid=jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class);
        try {
            var total=oversizedRequest(user.accessToken());
            assertThat(total.statusCode()).as(total.body()).isEqualTo(413);
            assertThat(total.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            assertThat(total.body()).doesNotContain("storageKey","sha256","Exception","private-images");
        } catch (java.io.IOException closedDuringResponse) {
            // The servlet can close the connection while the client is still sending the rejected body.
            assertThat(closedDuringResponse).hasMessageContaining("chunked transfer encoding");
            System.out.println("STORAGE_MULTIPART_TOTAL_LIMIT: connection closed before complete HTTP response");
        }
        assertThat(jdbc.queryForObject("select count(*) from storage_media_assets",Long.class)).isEqualTo(assets);
        assertThat(jdbc.queryForObject("select count(*) from storage_blobs",Long.class)).isEqualTo(blobs);
        assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits where completed=false",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from storage_upload_permits",Long.class)).isEqualTo(afterInvalid);
        assertThat(physicalFiles()).isEqualTo(physical);
    }
}
