package com.bilimili.buaa13.controller;

import com.bilimili.buaa13.entity.User;
import com.bilimili.buaa13.mapper.UserMapper;
import com.bilimili.buaa13.service.utils.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 由现有 JWT 过滤器保护，只返回消息业务所需的用户资料。2026.11.26 */
@RestController
@RequestMapping("/internal/message")
public class MessageIdentityController {
    private final CurrentUser currentUser;
    private final UserMapper users;
    public MessageIdentityController(CurrentUser currentUser,UserMapper users) { this.currentUser=currentUser; this.users=users; }
    @GetMapping("/session")
    public Map<String,Object> session() { return user(currentUser.getUserId()); }
    @GetMapping("/users/{uid}")
    public Map<String,Object> user(@PathVariable Integer uid) {
        User user=users.selectById(uid);
        if(user==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"User not found");
        if(user.getState()!=null && user.getState()!=0) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"User unavailable");
        Map<String,Object> result=new LinkedHashMap<>(); result.put("uid",user.getUid());
        result.put("nickname",user.getNickname()); result.put("avatar_url",user.getHeadPortrait()); return result;
    }
}
