package com.aichat.Mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.aichat.Entity.TaskRun;

@Mapper
public interface TaskRunMapper {

    @Insert("INSERT INTO tb_task_run(task_id, user_id, action_type, trigger_type, status, detail, "
            + "started_at, finished_at) VALUES(#{taskId}, #{userId}, #{actionType}, #{triggerType}, "
            + "#{status}, #{detail}, #{startedAt}, #{finishedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TaskRun run);

    // 某条任务的执行历史（新的在前）
    @Select("SELECT id, task_id, user_id, action_type, trigger_type, status, detail, started_at, "
            + "finished_at, create_time FROM tb_task_run "
            + "WHERE task_id = #{taskId} AND user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<TaskRun> selectByTask(@Param("taskId") long taskId,
            @Param("userId") String userId,
            @Param("limit") int limit);
}
