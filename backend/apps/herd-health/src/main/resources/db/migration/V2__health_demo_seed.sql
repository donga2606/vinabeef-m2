INSERT INTO farm (farm_id, farm_name) VALUES
    ('FARM-DL', 'Dak Lak'),
    ('FARM-GL', 'Gia Lai');

INSERT INTO barn (barn_id, farm_id, barn_name) VALUES
    ('BARN-DL-01', 'FARM-DL', 'Dak Lak barn 01'),
    ('BARN-DL-02', 'FARM-DL', 'Dak Lak barn 02'),
    ('BARN-GL-01', 'FARM-GL', 'Gia Lai barn 01');

INSERT INTO cattle (cattle_id, rfid, display_name, registration_status, resource_version, registered_at) VALUES
    ('10000000-0000-4000-8000-000000000001', 'RFID-DL-1001', 'Cow DL 1001', 'ACTIVE', 0, '2026-11-01T00:00:00Z'),
    ('10000000-0000-4000-8000-000000000002', 'RFID-DL-1002', 'Cow DL 1002', 'ACTIVE', 0, '2026-11-01T00:00:00Z'),
    ('10000000-0000-4000-8000-000000000003', 'RFID-GL-2001', 'Cow GL 2001', 'ACTIVE', 0, '2026-11-01T00:00:00Z'),
    ('10000000-0000-4000-8000-000000000099', 'RFID-UNKNOWN-99', 'Unverified tag', 'UNKNOWN', 0, '2026-11-01T00:00:00Z');

INSERT INTO cattle_custody (cattle_id, farm_id, barn_id, custody_version, updated_at) VALUES
    ('10000000-0000-4000-8000-000000000001', 'FARM-DL', 'BARN-DL-01', 0, '2026-11-01T00:00:00Z'),
    ('10000000-0000-4000-8000-000000000002', 'FARM-DL', 'BARN-DL-02', 0, '2026-11-01T00:00:00Z'),
    ('10000000-0000-4000-8000-000000000003', 'FARM-GL', 'BARN-GL-01', 0, '2026-11-01T00:00:00Z');

INSERT INTO vaccination_schedule (schedule_id, cattle_id, vaccine_code, due_at, status, created_at) VALUES
    ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'FMD-BOOSTER', '2026-11-20T08:00:00Z', 'SCHEDULED', '2026-11-10T00:00:00Z');
