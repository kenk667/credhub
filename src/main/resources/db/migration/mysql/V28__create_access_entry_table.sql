CREATE TABLE `access_entry`(
  `uuid` BINARY(16) NOT NULL,
  `secret_name_uuid` BINARY(16) NOT NULL,
  `actor` VARCHAR(255) NOT NULL,
  `read_permission` BOOLEAN NOT NULL DEFAULT 0,
  `write_permission` BOOLEAN NOT NULL DEFAULT 0
)  ENGINE=InnoDB DEFAULT CHARSET=utf8;

ALTER TABLE `access_entry`
  ADD CONSTRAINT `access_entry_pkey` PRIMARY KEY(uuid);

ALTER TABLE `access_entry`
  ADD CONSTRAINT `actor_resource_unique` UNIQUE(actor, secret_name_uuid);

ALTER TABLE `access_entry`
  ADD CONSTRAINT `secret_name_uuid_access_fkey`
  FOREIGN KEY(secret_name_uuid)
  REFERENCES secret_name(uuid)
  ON DELETE CASCADE;
