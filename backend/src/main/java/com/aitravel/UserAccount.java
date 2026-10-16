package com.aitravel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("app_user")
public class UserAccount {
    @TableId(type = IdType.AUTO)
    public Long userId;
    public String username;
    public String passwordHash;
    public String nickname;
    public String role;
}
