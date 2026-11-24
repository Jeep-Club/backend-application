package com.jeepclub.backend.storage.infra.image;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class SynchronousImageProcessorTest {
    @Test void deadlineAfterDecodeStopsProcessingAndReleasesCapacity() throws Exception {
        var processor = new SynchronousImageProcessor();
        byte[] input = image();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var expiration = new IllegalStateException("expired");
        for (int attempt = 0; attempt < 3; attempt++) {
            checks.set(0);
            assertThatThrownBy(() -> processor.process(input, SynchronousImageProcessor.Category.PROFILE,
                    List.of(), java.util.Map.of(), () -> {
                        if (checks.incrementAndGet() == 2) throw expiration;
                    })).isSameAs(expiration);
        }
        assertThat(processor.process(input, SynchronousImageProcessor.Category.PROFILE, List.of()).master()).isNotNull();
    }

    @Test void rejectsReductionBelowAvailableGalleryResolution() throws Exception {
        var source = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_ARGB);
        var random = new java.util.Random(628);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, random.nextInt());
            }
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(source, "png", output);
        assertThat(output.size()).isBetween(5 * 1024 * 1024 + 1, SynchronousImageProcessor.MAX_INPUT);
        var processor = new SynchronousImageProcessor();
        var thumbnail = new SynchronousImageProcessor.Preset("THUMBNAIL", 240, 180, null);
        var gallery = new SynchronousImageProcessor.Preset("GALLERY", 1600, 1200, null);
        byte[] input = output.toByteArray();

        var reduced = processor.process(input, SynchronousImageProcessor.Category.OTHER, List.of(thumbnail));
        assertThat(reduced.master().bytes().length).isLessThanOrEqualTo(5 * 1024 * 1024);
        assertThat(reduced.variants().get(0).width()).isEqualTo(240);
        assertThatThrownBy(() -> processor.process(input, SynchronousImageProcessor.Category.OTHER, List.of(thumbnail, gallery)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MASTER cannot preserve variant resolution within category limit");
    }

    private byte[] image() throws Exception {
        var img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream(); ImageIO.write(img, "png", out); return out.toByteArray();
    }
    @Test void acceptsExactlyTenMibAndRejectsOneByteMore() throws Exception {
        byte[] png=image();int payloadSize=SynchronousImageProcessor.MAX_INPUT-png.length-12;
        byte[] padding=new byte[payloadSize];byte[] type={'n','p','T','c'};
        var crc=new java.util.zip.CRC32();crc.update(type);crc.update(padding);
        var output=new ByteArrayOutputStream(SynchronousImageProcessor.MAX_INPUT);
        var data=new java.io.DataOutputStream(output);
        data.write(png,0,png.length-12);data.writeInt(payloadSize);data.write(type);data.write(padding);data.writeInt((int)crc.getValue());data.write(png,png.length-12,12);
        byte[] exact=output.toByteArray();assertThat(exact).hasSize(SynchronousImageProcessor.MAX_INPUT);
        var processor=new SynchronousImageProcessor();
        assertThat(processor.process(exact,SynchronousImageProcessor.Category.PROFILE,List.of()).master().bytes()).isNotEmpty();
        assertThatThrownBy(() -> processor.process(java.util.Arrays.copyOf(exact,exact.length+1),SynchronousImageProcessor.Category.PROFILE,List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void derivesIndependentCropsAndHashesPersistedBytes() throws Exception {
        var processor = new SynchronousImageProcessor();
        var result = processor.process(image(), SynchronousImageProcessor.Category.PROFILE, List.of(
                new SynchronousImageProcessor.Preset("AVATAR_SMALL", 32, 32, new SynchronousImageProcessor.Crop(0, 0, 80, 80)),
                new SynchronousImageProcessor.Preset("CARD", 60, 80, new SynchronousImageProcessor.Crop(10, 0, 60, 80))));
        assertThat(result.master().bytes().length).isLessThanOrEqualTo(2 * 1024 * 1024);
        assertThat(result.variants()).extracting(SynchronousImageProcessor.Image::width).containsExactly(32, 60);
        assertThat(result.variants()).extracting(SynchronousImageProcessor.Image::height).containsExactly(32, 80);
        assertThat(result.variants()).extracting(SynchronousImageProcessor.Image::mimeType).containsExactly("image/webp", "image/webp");
        assertThat(result.master().sha256()).hasSize(64);
        assertThat(result.receivedSha256()).isEqualTo(result.master().sha256());
    }
    @Test void returnedBytesCannotMutateTheHashedRepresentation() throws Exception {
        var result = new SynchronousImageProcessor().process(image(), SynchronousImageProcessor.Category.PROFILE, List.of());
        byte[] original = result.master().bytes();
        byte[] exposed = result.master().bytes();
        exposed[0] ^= 1;
        assertThat(result.master().bytes()).containsExactly(original);
    }
    @Test void normalizedCropsAreIndependentAndOmissionDoesNotCrop() throws Exception {
        var processor = new SynchronousImageProcessor();
        var presets = List.of(new SynchronousImageProcessor.Preset("AVATAR_SMALL", 64, 64, null),
                new SynchronousImageProcessor.Preset("CARD", 64, 64, null));
        byte[] source = image();
        var uncropped = processor.process(source, SynchronousImageProcessor.Category.PROFILE, presets);
        var crops = java.util.Map.of("AVATAR_SMALL", new com.jeepclub.backend.storage.core.repository.MediaImageProcessor.Crop(0, 0, 2d/3d, 1),
                "CARD", new com.jeepclub.backend.storage.core.repository.MediaImageProcessor.Crop(0, 0, .5, 1));
        var cropped = processor.process(source, SynchronousImageProcessor.Category.PROFILE, presets, crops);
        assertThat(uncropped.variants().get(0).width()).isEqualTo(64);
        assertThat(uncropped.variants().get(0).height()).isEqualTo(43);
        assertThat(cropped.variants().get(0).width()).isEqualTo(64);
        assertThat(cropped.variants().get(0).height()).isEqualTo(64);
        assertThat(cropped.variants().get(1).width()).isEqualTo(48);
        assertThat(cropped.variants().get(1).height()).isEqualTo(64);
        assertThat(cropped.master().bytes()).containsExactly(uncropped.master().bytes());
        assertThatThrownBy(() -> new com.jeepclub.backend.storage.core.repository.MediaImageProcessor.Crop(Double.NaN, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.jeepclub.backend.storage.core.repository.MediaImageProcessor.Crop(.5, 0, .6, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.process(source, SynchronousImageProcessor.Category.PROFILE, presets,
                java.util.Map.of("MASTER", crops.get("CARD")))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidInputDuplicatePresetsAndOutOfBoundsCrop() throws Exception {
        var processor = new SynchronousImageProcessor();
        assertThatThrownBy(() -> processor.process(new byte[11 * 1024 * 1024], SynchronousImageProcessor.Category.OTHER, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.process(new byte[]{1,2,3}, SynchronousImageProcessor.Category.OTHER, List.of())).isInstanceOf(IllegalArgumentException.class);
        var preset = new SynchronousImageProcessor.Preset("FEED", 32, 32, new SynchronousImageProcessor.Crop(100, 0, 30, 30));
        assertThatThrownBy(() -> processor.process(image(), SynchronousImageProcessor.Category.OTHER, List.of(preset))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.process(image(), SynchronousImageProcessor.Category.OTHER, List.of(preset, preset))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsTruncatedJpegAndPngWithBrokenCrc() throws Exception {
        var processor = new SynchronousImageProcessor();
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB), "jpeg", out);
        byte[] jpeg = out.toByteArray();
        assertThatThrownBy(() -> processor.process(java.util.Arrays.copyOf(jpeg, jpeg.length - 2),
                SynchronousImageProcessor.Category.PROFILE, List.of())).isInstanceOf(IllegalArgumentException.class);
        byte[] png = image();
        png[29] ^= 1; // IHDR CRC, without altering the dimensions.
        assertThatThrownBy(() -> processor.process(png, SynchronousImageProcessor.Category.PROFILE, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] valid = image();
        assertThatThrownBy(() -> processor.process(java.util.Arrays.copyOf(valid, valid.length - 12),
                SynchronousImageProcessor.Category.PROFILE, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsOverBudgetPngHeadersBeforeReadingPixels() throws Exception {
        var processor = new SynchronousImageProcessor();
        for (int[] dimensions : new int[][]{{10_001, 1}, {1, 10_001}, {5_001, 4_000}}) {
            byte[] png = image();
            // Keep the small IDAT and repair the header CRC so the dimension check must reject it.
            var header = java.nio.ByteBuffer.wrap(png);
            header.putInt(16, dimensions[0]);
            header.putInt(20, dimensions[1]);
            var crc = new java.util.zip.CRC32();
            crc.update(png, 12, 17);
            header.putInt(29, (int) crc.getValue());
            assertThatThrownBy(() -> processor.process(png, SynchronousImageProcessor.Category.PROFILE, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Image dimensions exceed limits");
        }
    }

    @Test void rejectsDecoderResultsWithDimensionsThatDifferFromHeader() {
        var decoded = new BufferedImage(16, 12, BufferedImage.TYPE_INT_RGB);
        assertThat(SynchronousImageProcessor.requireDecodedDimensions(decoded, 16, 12)).isSameAs(decoded);
        assertThatThrownBy(() -> SynchronousImageProcessor.requireDecodedDimensions(decoded, 12, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SynchronousImageProcessor.requireDecodedDimensions(decoded, 16, 16))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SynchronousImageProcessor.requireDecodedDimensions(null, 16, 12))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void pngMasterDropsTextWithoutRecompressingPixelsOrLosingTransparency() throws Exception {
        var input = new BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB);
        input.setRGB(2, 3, 0x40112233);
        var encoded = new ByteArrayOutputStream(); ImageIO.write(input, "png", encoded);
        byte[] original = encoded.toByteArray();
        byte[] text = "Comment\0private location".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var chunk = new ByteArrayOutputStream();
        var data = new java.io.DataOutputStream(chunk);
        data.writeInt(text.length); data.writeBytes("tEXt"); data.write(text);
        var crc = new java.util.zip.CRC32(); crc.update("tEXt".getBytes(java.nio.charset.StandardCharsets.US_ASCII)); crc.update(text);
        data.writeInt((int) crc.getValue());
        var withMetadata = new ByteArrayOutputStream();
        withMetadata.write(original, 0, 33); withMetadata.write(chunk.toByteArray()); withMetadata.write(original, 33, original.length - 33);
        var result = new SynchronousImageProcessor().process(withMetadata.toByteArray(), SynchronousImageProcessor.Category.PROFILE, List.of());
        assertThat(result.master().mimeType()).isEqualTo("image/png");
        assertThat(result.master().bytes()).containsExactly(original);
        assertThat(result.receivedSha256()).isNotEqualTo(result.master().sha256());
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.master().bytes())).getRGB(2,3)).isEqualTo(0x40112233);
    }
    @Test void webpCodecPreservesTransparencyAndHasSafeFallbacks() throws Exception {
        assertThat(dev.matrixlab.webp4j.WebPCodec.isAvailable()).isTrue();
        var image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0x40112233);
        var encoded = SynchronousImageProcessor.encodePresentation(image,
                value -> dev.matrixlab.webp4j.WebPCodec.encodeLosslessImage(value));
        assertThat(encoded.mime()).isEqualTo("image/webp");
        assertThat(dev.matrixlab.webp4j.WebPCodec.decodeImage(encoded.bytes()).getRGB(2,3)).isEqualTo(0x40112233);
        var pngFallback = SynchronousImageProcessor.encodePresentation(image, value -> { throw new java.io.IOException("codec failed"); });
        assertThat(pngFallback.mime()).isEqualTo("image/png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(pngFallback.bytes())).getRGB(2,3)).isEqualTo(0x40112233);
        var jpegFallback = SynchronousImageProcessor.encodePresentation(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB), value -> new byte[]{1,2});
        assertThat(jpegFallback.mime()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(jpegFallback.bytes()))).isNotNull();
    }
    @Test void webpMasterRemovesMetadataWithoutRecompressingImage() throws Exception {
        var image = new BufferedImage(12, 8, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2,3,0x40112233);
        byte[] original=dev.matrixlab.webp4j.WebPCodec.encodeLosslessImage(image);
        var out=new ByteArrayOutputStream(); out.write(original);
        byte[] metadata="private-location".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(new byte[]{'X','M','P',' ',(byte)metadata.length,0,0,0}); out.write(metadata);
        if ((metadata.length&1)!=0) out.write(0);
        byte[] uploaded=out.toByteArray();
        java.nio.ByteBuffer.wrap(uploaded,4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(uploaded.length-8);
        var result=new SynchronousImageProcessor().process(uploaded,SynchronousImageProcessor.Category.PROFILE,List.of());
        assertThat(result.master().mimeType()).isEqualTo("image/webp");
        assertThat(result.master().bytes()).containsExactly(original);
        assertThat(result.receivedSha256()).isNotEqualTo(result.master().sha256());
        assertThat(dev.matrixlab.webp4j.WebPCodec.decodeImage(result.master().bytes()).getRGB(2,3)).isEqualTo(0x40112233);
    }
    @Test void jpegMasterRemovesExifAndCommentsWithoutRecompressingScan() throws Exception {
        var image=new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<60;y++) for(int x=0;x<80;x++) image.setRGB(x,y,(x*3<<16)|(y*4<<8)|(x+y));
        var jpeg=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",jpeg);byte[] original=jpeg.toByteArray();
        var exif=java.nio.ByteBuffer.allocate(36);
        exif.put((byte)255).put((byte)225).putShort((short)34).put(new byte[]{'E','x','i','f',0,0});
        exif.putShort((short)0x4d4d).putShort((short)42).putInt(8).putShort((short)1);
        exif.putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)1).putShort((short)0).putInt(0);
        byte[] comment="GPS private fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var upload=new ByteArrayOutputStream();upload.write(original,0,2);upload.write(exif.array());
        upload.write(255);upload.write(254);upload.write(0);upload.write(comment.length+2);upload.write(comment);
        upload.write(original,2,original.length-2);
        var result=new SynchronousImageProcessor().process(upload.toByteArray(),SynchronousImageProcessor.Category.PROFILE,List.of());
        assertThat(result.master().mimeType()).isEqualTo("image/jpeg");
        assertThat(new String(result.master().bytes(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif","GPS private fixture");
        assertThat(scanData(result.master().bytes())).containsExactly(scanData(original));
        var before=ImageIO.read(new java.io.ByteArrayInputStream(original));
        var after=ImageIO.read(new java.io.ByteArrayInputStream(result.master().bytes()));
        assertThat(after.getRGB(0,0,80,60,null,0,80)).containsExactly(before.getRGB(0,0,80,60,null,0,80));
    }
    @Test void progressiveJpegKeepsItsScansWhenNoTransformationIsNeeded() throws Exception {
        var image=new BufferedImage(40,30,BufferedImage.TYPE_INT_RGB);image.setRGB(4,5,0x442266);
        var writer=ImageIO.getImageWritersByFormatName("jpeg").next();var out=new ByteArrayOutputStream();
        try(var stream=ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);var param=writer.getDefaultWriteParam();param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null,new javax.imageio.IIOImage(image,null,null),param);stream.flush();
        } finally {writer.dispose();}
        byte[] original=out.toByteArray();
        var result=new SynchronousImageProcessor().process(original,SynchronousImageProcessor.Category.OTHER,List.of());
        assertThat(result.master().mimeType()).isEqualTo("image/jpeg");
        assertThat(scanData(result.master().bytes())).containsExactly(scanData(original));
    }
    @Test void oversizedMastersAreReducedToTheirCategoryLimits() throws Exception {
        var image=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);
        var random=new java.util.Random(573L);int[] pixels=new int[1920*1080];
        for(int i=0;i<pixels.length;i++) pixels[i]=random.nextInt(0x1000000);
        image.setRGB(0,0,1920,1080,pixels,0,1920);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);byte[] input=out.toByteArray();
        assertThat(input.length).isGreaterThan(5*1024*1024).isLessThanOrEqualTo(SynchronousImageProcessor.MAX_INPUT);
        var processor=new SynchronousImageProcessor();
        var profile=processor.process(input,SynchronousImageProcessor.Category.PROFILE,List.of());
        var other=processor.process(input,SynchronousImageProcessor.Category.OTHER,List.of());
        assertThat(profile.master().bytes().length).isLessThanOrEqualTo(2*1024*1024);
        assertThat(other.master().bytes().length).isLessThanOrEqualTo(5*1024*1024);
        assertThat(profile.master().width()).isLessThanOrEqualTo(1920);
        assertThat(other.master().height()).isLessThanOrEqualTo(1080);
    }
    private static byte[] scanData(byte[] jpeg) {
        for(int i=0;i<jpeg.length-1;i++) if((jpeg[i]&255)==255 && (jpeg[i+1]&255)==0xda)
            return java.util.Arrays.copyOfRange(jpeg,i,jpeg.length);
        throw new AssertionError("Missing scan");
    }
}
