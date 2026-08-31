-- ============================================================================
-- Baseline схемы шлюза. Снят pg_dump'ом со схемы, которую раньше создавал
-- Hibernate (ddl-auto=update) — один-в-один, чтобы ddl-auto=validate проходил.
-- ДАЛЬШЕ схему меняем ТОЛЬКО новыми миграциями Vn__*.sql, НЕ Hibernate.
-- ============================================================================

CREATE TABLE public.controllers (
    id bigint NOT NULL,
    created_at timestamp(6) without time zone,
    description character varying(255),
    enabled boolean,
    endpoint character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    password character varying(255),
    security_policy character varying(255),
    updated_at timestamp(6) without time zone,
    username character varying(255)
);

CREATE SEQUENCE public.controllers_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE public.controllers_id_seq OWNED BY public.controllers.id;

CREATE TABLE public.event_log (
    id bigint NOT NULL,
    acknowledged boolean,
    controller_id bigint,
    details text,
    event_time timestamp(6) with time zone NOT NULL,
    event_type character varying(50) NOT NULL,
    message character varying(500),
    severity character varying(20),
    source character varying(100),
    tag_id bigint,
    user_id character varying(255)
);

CREATE SEQUENCE public.event_log_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE public.event_log_id_seq OWNED BY public.event_log.id;

CREATE TABLE public.tags (
    id bigint NOT NULL,
    channel_id bigint,
    created_at timestamp(6) without time zone,
    data_type character varying(255) NOT NULL,
    description character varying(255),
    device_name character varying(255),
    device_type character varying(255),
    enabled boolean,
    field_name character varying(255),
    fields_json text,
    max_value double precision,
    min_value double precision,
    modbus_address integer,
    modbus_type character varying(255),
    modbus_unit_id integer,
    name character varying(255) NOT NULL,
    node_id character varying(255) NOT NULL,
    polling_rate bigint,
    protocol character varying(255) NOT NULL,
    record_device boolean,
    unit character varying(255),
    updated_at timestamp(6) without time zone,
    writable boolean,
    controller_id bigint NOT NULL
);

CREATE SEQUENCE public.tags_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE public.tags_id_seq OWNED BY public.tags.id;

CREATE TABLE public.telemetry (
    id bigint NOT NULL,
    quality character varying(20),
    raw_data bytea,
    tag_id bigint NOT NULL,
    "time" timestamp(6) with time zone NOT NULL,
    value double precision,
    value_str character varying(255)
);

CREATE SEQUENCE public.telemetry_id_seq
    START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE public.telemetry_id_seq OWNED BY public.telemetry.id;

ALTER TABLE ONLY public.controllers ALTER COLUMN id SET DEFAULT nextval('public.controllers_id_seq'::regclass);
ALTER TABLE ONLY public.event_log ALTER COLUMN id SET DEFAULT nextval('public.event_log_id_seq'::regclass);
ALTER TABLE ONLY public.tags ALTER COLUMN id SET DEFAULT nextval('public.tags_id_seq'::regclass);
ALTER TABLE ONLY public.telemetry ALTER COLUMN id SET DEFAULT nextval('public.telemetry_id_seq'::regclass);

ALTER TABLE ONLY public.controllers ADD CONSTRAINT controllers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.event_log ADD CONSTRAINT event_log_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.tags ADD CONSTRAINT tags_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.telemetry ADD CONSTRAINT telemetry_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.controllers ADD CONSTRAINT uk_qe4t6813qosfo6skel2wdy3ln UNIQUE (name);
ALTER TABLE ONLY public.tags ADD CONSTRAINT fksuv2x2cjnvh1nta547gq5ilvv FOREIGN KEY (controller_id) REFERENCES public.controllers(id);
