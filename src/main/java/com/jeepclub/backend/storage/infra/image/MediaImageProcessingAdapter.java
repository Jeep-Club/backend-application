package com.jeepclub.backend.storage.infra.image;

import com.jeepclub.backend.storage.core.repository.MediaImageProcessor;
import com.jeepclub.backend.storage.core.domain.enums.MediaContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@Component
public class MediaImageProcessingAdapter implements MediaImageProcessor {
    private final IsolatedImageCodec processor = new IsolatedImageCodec();
    private final MeterRegistry metrics;
    public MediaImageProcessingAdapter(MeterRegistry metrics) { this.metrics=metrics; }
    @Override public Processed process(byte[] input, MediaContext context) {
        return process(input, context, Options.defaults());
    }
    @Override public Processed process(byte[] input, MediaContext context, Options options) {
        return process(input, context, options, () -> {});
    }
    @Override public Processed process(byte[] input, MediaContext context, Options options, Runnable checkDeadline) {
        long start=System.nanoTime(); String outcome="failed";
        try {
            if (context == null || options == null) throw new IllegalArgumentException("Invalid image options");
            boolean profile=context==MediaContext.IDENTITY_PROFILE || context==MediaContext.DEPENDENT_PHOTO;
            var presets=new ArrayList<>(profile ? List.of(new SynchronousImageProcessor.Preset("AVATAR_SMALL",144,144,null),
                new SynchronousImageProcessor.Preset("PROFILE_PREVIEW",320,320,null))
                : List.of(new SynchronousImageProcessor.Preset("THUMBNAIL",240,180,null),
                new SynchronousImageProcessor.Preset("GALLERY",1600,1200,null)));
            // Card export is opt-in: its bounds are the existing MASTER ceiling, not a UI measurement.
            if (context==MediaContext.IDENTITY_PROFILE && options.crops().containsKey("CARD_PORTRAIT"))
                presets.add(new SynchronousImageProcessor.Preset("CARD_PORTRAIT",4096,4096,null));
            var codes=presets.stream().map(SynchronousImageProcessor.Preset::code).collect(java.util.stream.Collectors.toSet());
            if (!codes.containsAll(options.crops().keySet())) throw new IllegalArgumentException("Unknown crop variant");
            var request = new ImageCodecWorker.Request(UUID.randomUUID(), input,
                    profile ? SynchronousImageProcessor.Category.PROFILE : SynchronousImageProcessor.Category.OTHER,
                    presets, options.crops());
            var result = processor.process(request, checkDeadline);
            outcome="success"; return result;
        }
        finally { metrics.timer("storage.image.processing","outcome",outcome).record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS); }
    }
}
