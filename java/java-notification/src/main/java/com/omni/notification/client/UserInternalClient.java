package com.omni.notification.client;

import com.omni.notification.dto.InternalUserContactResponse;
import com.omni.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "java-user")
public interface UserInternalClient {

    @GetMapping("/api/user/internal/{id}/contact")
    Result<InternalUserContactResponse> getUserContact(@PathVariable("id") Long id,
                                                       @RequestHeader("X-Internal-Token") String internalToken);
}
