package com.jeepclub.backend.storage.infra.image;

import com.jeepclub.backend.storage.core.domain.enums.MediaContext;
import com.jeepclub.backend.storage.core.repository.MediaImageProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MediaImageProcessingAdapterTest {
    @Test void rectangularSourceIsNotImplicitlyCroppedForAvatar() throws Exception {
        var adapter=new MediaImageProcessingAdapter(new SimpleMeterRegistry());
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(800,400,BufferedImage.TYPE_INT_RGB),"png",out);
        var result=adapter.process(out.toByteArray(),MediaContext.IDENTITY_PROFILE);
        var avatar=result.images().stream().filter(i -> i.code().equals("AVATAR_SMALL")).findFirst().orElseThrow();
        assertThat(avatar.width()).isEqualTo(144);
        assertThat(avatar.height()).isEqualTo(72);
        var cropped=adapter.process(out.toByteArray(),MediaContext.IDENTITY_PROFILE,
            new MediaImageProcessor.Options(Map.of("AVATAR_SMALL",new MediaImageProcessor.Crop(0,0,.5,1))));
        var square=cropped.images().stream().filter(i -> i.code().equals("AVATAR_SMALL")).findFirst().orElseThrow();
        assertThat(square.width()).isEqualTo(144);
        assertThat(square.height()).isEqualTo(144);
    }
    private static double gradientPsnr(byte[] encoded,String mime) throws Exception {
        BufferedImage decoded=mime.equals("image/webp") ? dev.matrixlab.webp4j.WebPCodec.decodeImage(encoded) : ImageIO.read(new java.io.ByteArrayInputStream(encoded));
        double error=0;int w=decoded.getWidth(),h=decoded.getHeight();
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int rgb=decoded.getRGB(x,y);double ex=(x+.5)*255/w,ey=(y+.5)*255/h;
            error+=Math.pow(((rgb>>16)&255)-ex,2)+Math.pow(((rgb>>8)&255)-ey,2)+Math.pow((rgb&255)-(ex+ey)/2,2);
        }
        return 10*Math.log10(255d*255/(error/(3d*w*h)));
    }
    @Test void avatarPresetMatchesHighDensityAndDoesNotUpscale() throws Exception {
        var adapter=new MediaImageProcessingAdapter(new SimpleMeterRegistry());
        var image=new BufferedImage(800,800,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<800;y++) for(int x=0;x<800;x++) image.setRGB(x,y,((x*255/799)<<16)|((y*255/799)<<8)|((x+y)*255/1598));
        var input=new ByteArrayOutputStream();ImageIO.write(image,"png",input);
        long start=System.nanoTime();var result=adapter.process(input.toByteArray(),MediaContext.IDENTITY_PROFILE);
        long elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        var avatar=result.images().stream().filter(i -> i.code().equals("AVATAR_SMALL")).findFirst().orElseThrow();
        assertThat(avatar.width()).isEqualTo(144);assertThat(avatar.height()).isEqualTo(144);
        var baseline=new SynchronousImageProcessor().process(input.toByteArray(),SynchronousImageProcessor.Category.PROFILE,
            java.util.List.of(new SynchronousImageProcessor.Preset("AVATAR_SMALL",64,64,null))).variants().get(0);
        double quality=gradientPsnr(avatar.bytes(),avatar.mimeType()),oldQuality=gradientPsnr(baseline.bytes(),baseline.mimeType());
        assertThat(quality).isGreaterThan(30);
        System.out.println("BACK636_AVATAR_FIXTURE input=800x800 png_bytes="+input.size()+" old="+baseline.width()+"x"+baseline.height()+" old_bytes="+baseline.bytes().length+" old_psnr_db="+oldQuality+" new="+avatar.width()+"x"+avatar.height()+" new_bytes="+avatar.bytes().length+" new_psnr_db="+quality+" mime="+avatar.mimeType()+" processing_ms="+elapsed);
        var crops=new MediaImageProcessor.Options(Map.of("CARD_PORTRAIT",new MediaImageProcessor.Crop(0,0,.5,.71875)));
        var card=adapter.process(input.toByteArray(),MediaContext.IDENTITY_PROFILE,crops).images().stream().filter(i -> i.code().equals("CARD_PORTRAIT")).findFirst().orElseThrow();
        assertThat(card.width()).isEqualTo(400);assertThat(card.height()).isEqualTo(575);
        System.out.println("BACK636_CARD_FIXTURE crop=400x575 bytes="+card.bytes().length+" mime="+card.mimeType()+" target_export_photo_approx=283x408");
        var tiny=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(40,30,BufferedImage.TYPE_INT_RGB),"png",tiny);
        var noUpscale=adapter.process(tiny.toByteArray(),MediaContext.IDENTITY_PROFILE).images().get(1);
        assertThat(noUpscale.width()).isEqualTo(40);assertThat(noUpscale.height()).isEqualTo(30);
    }
    @Test void cardIsOptInAndUsesIndependentPortraitCrop() throws Exception {
        var adapter=new MediaImageProcessingAdapter(new SimpleMeterRegistry());
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(120,80,BufferedImage.TYPE_INT_RGB),"png",out);
        byte[] bytes=out.toByteArray();
        var standard=adapter.process(bytes,MediaContext.IDENTITY_PROFILE);
        assertThat(standard.images()).extracting(MediaImageProcessor.Representation::code)
                .containsExactly("MASTER","AVATAR_SMALL","PROFILE_PREVIEW");
        var options=new MediaImageProcessor.Options(Map.of(
                "AVATAR_SMALL",new MediaImageProcessor.Crop(0,0,2d/3d,1),
                "CARD_PORTRAIT",new MediaImageProcessor.Crop(0,0,1d/3d,1)));
        var result=adapter.process(bytes,MediaContext.IDENTITY_PROFILE,options);
        assertThat(result.images()).extracting(MediaImageProcessor.Representation::code)
                .containsExactly("MASTER","AVATAR_SMALL","PROFILE_PREVIEW","CARD_PORTRAIT");
        assertThat(result.images().get(0).bytes()).containsExactly(standard.images().get(0).bytes());
        assertThat(result.images().get(1).width()).isEqualTo(result.images().get(1).height());
        assertThat(result.images().get(3).width()).isEqualTo(40);
        assertThat(result.images().get(3).height()).isEqualTo(80);
        assertThatThrownBy(() -> adapter.process(bytes,MediaContext.DEPENDENT_PHOTO,options)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.process(bytes,MediaContext.IDENTITY_PROFILE,
                new MediaImageProcessor.Options(Map.of("CARD_PORTRAIT",new MediaImageProcessor.Crop(0,0,1,1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.process(bytes,MediaContext.IDENTITY_PROFILE,
                new MediaImageProcessor.Options(Map.of("AVATAR_SMALL",new MediaImageProcessor.Crop(0,0,1,1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
