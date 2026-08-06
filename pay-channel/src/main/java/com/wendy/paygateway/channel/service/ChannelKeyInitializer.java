package com.wendy.paygateway.channel.service;

import com.wendy.paygateway.channel.entity.PayChannelConfig;
import com.wendy.paygateway.common.util.RsaUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Sandbox channel key bootstrap: generates a 2048-bit key pair for RSA2 channels on the spot and
 * writes it back.
 *
 * <p>In a real deployment the private key comes from a KMS or config centre and is never stored in
 * plaintext. This exists purely so the local sandbox can exercise the full
 * "sign with merchant private key -&gt; verify with channel public key" chain out of the box.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ChannelKeyInitializer implements ApplicationRunner {

    private final PayChannelConfigService channelConfigService;

    @Override
    public void run(ApplicationArguments args) {
        channelConfigService.refresh();
        for (PayChannelConfig config : channelConfigService.listAll()) {
            if (!"RSA2".equalsIgnoreCase(config.getSignType())) {
                continue;
            }
            if (config.getPrivateKey() != null && !config.getPrivateKey().isBlank()) {
                continue;
            }
            RsaUtils.Rsa2KeyPair keyPair = RsaUtils.generateKeyPair();
            channelConfigService.updateKeyPair(config.getId(), keyPair.getPrivateKey(), keyPair.getPublicKey());
            log.info("[Channel keys] generated an RSA2 sandbox key pair for {}", config.getChannelType());
        }
        channelConfigService.refresh();
    }
}
