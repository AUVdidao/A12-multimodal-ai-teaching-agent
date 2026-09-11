CREATE OR REPLACE FUNCTION reject_locked_specification_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'LOCKED_SPECIFICATION_IMMUTABLE';
END;
$$;

DROP TRIGGER IF EXISTS locked_specifications_immutable_trg ON locked_specifications;

CREATE TRIGGER locked_specifications_immutable_trg
    BEFORE UPDATE OR DELETE ON locked_specifications
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_specification_mutation();
