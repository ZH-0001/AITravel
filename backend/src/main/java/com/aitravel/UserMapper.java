package com.aitravel;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserAccount> {
    @Select("SELECT user_id, username, password_hash, nickname, role FROM app_user WHERE username = #{username}")
    UserAccount findByUsername(String username);
}
