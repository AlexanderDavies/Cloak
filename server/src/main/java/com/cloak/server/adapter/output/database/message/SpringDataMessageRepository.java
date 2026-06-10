package com.cloak.server.adapter.output.database.message;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataMessageRepository extends JpaRepository<EncryptedMessageEntity, UUID> {}
