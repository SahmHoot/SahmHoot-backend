CREATE TABLE question_images (
  image_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  uploader_id BIGINT NOT NULL,
  question_set_id BIGINT NULL,
  status ENUM ('UPLOADING', 'AVAILABLE', 'DELETE_PENDING') NOT NULL,
  mime_type ENUM ('image/jpeg', 'image/png') NOT NULL,
  size_bytes INT NOT NULL,
  width INT NOT NULL,
  height INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  cleanup_after DATETIME(6) NOT NULL,
  INDEX ix_question_images_cleanup (cleanup_after, image_key),
  CONSTRAINT fk_question_images_uploader FOREIGN KEY (uploader_id) REFERENCES users (id),
  CONSTRAINT fk_question_images_set FOREIGN KEY (question_set_id) REFERENCES question_sets (id) ON DELETE SET NULL,
  CONSTRAINT ck_question_images_dimensions CHECK (
    width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096 AND width * height <= 16000000
  ),
  CONSTRAINT ck_question_images_size CHECK (size_bytes BETWEEN 1 AND 5242880)
);

ALTER TABLE questions
  ADD COLUMN image_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD CONSTRAINT fk_questions_image FOREIGN KEY (image_key) REFERENCES question_images (image_key);

ALTER TABLE run_questions
  ADD COLUMN image_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD CONSTRAINT fk_run_questions_image FOREIGN KEY (image_key) REFERENCES question_images (image_key);
