package com.studenthaven.android.data;

import androidx.annotation.NonNull;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Delete;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.RoomDatabase;
import androidx.room.Update;
import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.UUID;

@Database(entities = {StudyDatabase.Course.class, StudyDatabase.Note.class,
        StudyDatabase.Flashcard.class, StudyDatabase.Assignment.class},
        version = 1, exportSchema = true)
public abstract class StudyDatabase extends RoomDatabase {
    public abstract StudyDao studyDao();

    @Entity(tableName = "courses")
    public static class Course {
        @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
        @NonNull public String name = "";
        @NonNull public String code = "";
        @NonNull public String professor = "";
        @NonNull public String location = "";
    }

    @Entity(tableName = "notes", foreignKeys = @ForeignKey(entity = Course.class,
            parentColumns = "id", childColumns = "courseId", onDelete = ForeignKey.CASCADE),
            indices = @Index("courseId"))
    public static class Note {
        @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
        @NonNull public String courseId = "";
        @NonNull public String title = "";
        @NonNull public String content = "";
        @NonNull public String tags = "";
    }

    @Entity(tableName = "flashcards", foreignKeys = {
            @ForeignKey(entity = Course.class, parentColumns = "id",
                    childColumns = "courseId", onDelete = ForeignKey.CASCADE),
            @ForeignKey(entity = Note.class, parentColumns = "id",
                    childColumns = "sourceNoteId", onDelete = ForeignKey.SET_NULL)},
            indices = {@Index("courseId"), @Index("sourceNoteId")})
    public static class Flashcard {
        @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
        @NonNull public String courseId = "";
        @NonNull public String front = "";
        @NonNull public String back = "";
        public String sourceNoteId;
        public int repetitions;
        public double easeFactor = 2.5;
        public int intervalDays;
        public long nextReviewAt = System.currentTimeMillis();
        public Long lastReviewedAt;
    }

    @Entity(tableName = "assignments", foreignKeys = @ForeignKey(entity = Course.class,
            parentColumns = "id", childColumns = "courseId", onDelete = ForeignKey.CASCADE),
            indices = @Index("courseId"))
    public static class Assignment {
        @PrimaryKey @NonNull public String id = UUID.randomUUID().toString();
        @NonNull public String courseId = "";
        @NonNull public String name = "";
        @NonNull public String dueDate = "";
        public Double score;
        public double maxScore = 100;
        public double weight = 1;
    }

    @Dao
    public interface StudyDao {
        @Query("SELECT * FROM courses ORDER BY name COLLATE NOCASE")
        LiveData<List<Course>> observeCourses();
        @Query("SELECT * FROM notes ORDER BY title COLLATE NOCASE")
        LiveData<List<Note>> observeNotes();
        @Query("SELECT * FROM flashcards ORDER BY nextReviewAt, front COLLATE NOCASE")
        LiveData<List<Flashcard>> observeFlashcards();
        @Query("SELECT * FROM assignments ORDER BY dueDate, name COLLATE NOCASE")
        LiveData<List<Assignment>> observeAssignments();
        @Insert void insertCourse(Course course);
        @Update int updateCourse(Course course);
        @Delete int deleteCourse(Course course);
        @Insert void insertNote(Note note);
        @Update int updateNote(Note note);
        @Delete int deleteNote(Note note);
        @Insert void insertFlashcards(List<Flashcard> cards);
        @Update int updateFlashcard(Flashcard card);
        @Delete int deleteFlashcard(Flashcard card);
        @Insert void insertAssignment(Assignment assignment);
        @Update int updateAssignment(Assignment assignment);
        @Delete int deleteAssignment(Assignment assignment);
    }
}
