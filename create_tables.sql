--
-- PostgreSQL database dump
--

-- Dumped from database version 11.1
-- Dumped by pg_dump version 11.1

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_with_oids = false;

--
-- Name: api_user; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.api_user (
    name character varying(128) NOT NULL,
    email character varying(128) NOT NULL,
    role integer NOT NULL,
    api_user_id character varying(128) NOT NULL
);


ALTER TABLE public.api_user OWNER TO jdog;

--
-- Name: game; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.game (
    name character varying(128) NOT NULL,
    description character varying(128) NOT NULL,
    variant character varying(128) NOT NULL,
    code character varying(128) NOT NULL,
    game_id bigint NOT NULL
);


ALTER TABLE public.game OWNER TO jdog;

--
-- Name: league; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.league (
    tournament_id bigint NOT NULL,
    pickee_description character varying(128) NOT NULL,
    name character varying(128) NOT NULL,
    url character varying(128) NOT NULL,
    transfer_blocked_during_period boolean NOT NULL,
    game_id bigint,
    apply_points_at_start_time boolean NOT NULL,
    api_key character varying(128) NOT NULL,
    transfer_delay_minutes integer NOT NULL,
    league_id bigint NOT NULL,
    transfer_open boolean NOT NULL,
    team_size integer NOT NULL,
    is_private boolean NOT NULL,
    url_verified boolean NOT NULL,
    transfer_limit integer,
    transfer_wildcard boolean NOT NULL,
    starting_money numeric(3,1) NOT NULL,
    no_wildcard_for_late_register boolean NOT NULL,
    period_description character varying(128) NOT NULL,
    current_period_id bigint
);


ALTER TABLE public.league OWNER TO jdog;

--
-- Name: league_prize; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.league_prize (
    email character varying(128) NOT NULL,
    description character varying(128) NOT NULL,
    league_id bigint NOT NULL,
    league_prize_id bigint NOT NULL
);


ALTER TABLE public.league_prize OWNER TO jdog;

--
-- Name: league_user; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.league_user (
    change_tstamp timestamp without time zone,
    used_wildcard boolean NOT NULL,
    remaining_transfers integer,
    league_id bigint NOT NULL,
    league_user_id bigint NOT NULL,
    money numeric(3,1) NOT NULL,
    user_id bigint NOT NULL,
    entered timestamp without time zone NOT NULL
);


ALTER TABLE public.league_user OWNER TO jdog;

--
-- Name: league_user_stat; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.league_user_stat (
    league_user_stat_id bigint NOT NULL,
    previous_rank integer NOT NULL,
    league_user_id bigint NOT NULL,
    stat_field_id bigint NOT NULL
);


ALTER TABLE public.league_user_stat OWNER TO jdog;

--
-- Name: league_user_stat_period; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.league_user_stat_period (
    league_user_stat_id bigint NOT NULL,
    league_user_stat_period_id bigint NOT NULL,
    value double precision NOT NULL,
    period integer NOT NULL
);


ALTER TABLE public.league_user_stat_period OWNER TO jdog;

--
-- Name: limit; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public."limit" (
    name character varying(128) NOT NULL,
    limit_type_id bigint NOT NULL,
    limit_id bigint NOT NULL,
    max integer NOT NULL
);


ALTER TABLE public."limit" OWNER TO jdog;

--
-- Name: limit_type; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.limit_type (
    name character varying(128) NOT NULL,
    description character varying(128) NOT NULL,
    league_id bigint NOT NULL,
    limit_type_id bigint NOT NULL,
    max integer
);


ALTER TABLE public.limit_type OWNER TO jdog;

--
-- Name: matchu; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.matchu (
    tournament_id bigint NOT NULL,
    added_db_tstamp timestamp without time zone NOT NULL,
    external_id bigint NOT NULL,
    league_id bigint NOT NULL,
    team_one_victory boolean NOT NULL,
    match_id bigint NOT NULL,
    team_one character varying(128) NOT NULL,
    targeted_at_tstamp timestamp without time zone NOT NULL,
    start_tstamp timestamp without time zone NOT NULL,
    team_two character varying(128) NOT NULL,
    period integer NOT NULL
);


ALTER TABLE public.matchu OWNER TO jdog;

--
-- Name: period; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.period (
    ended boolean NOT NULL,
    league_id bigint NOT NULL,
    period_id bigint NOT NULL,
    multiplier double precision NOT NULL,
    next_period_id bigint,
    value integer NOT NULL,
    timespan tstzrange
);


ALTER TABLE public.period OWNER TO jdog;

--
-- Name: pickee; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.pickee (
    name character varying(128) NOT NULL,
    external_id bigint NOT NULL,
    league_id bigint NOT NULL,
    pickee_id bigint NOT NULL,
    price numeric(3,1) NOT NULL,
    active boolean NOT NULL
);


ALTER TABLE public.pickee OWNER TO jdog;

--
-- Name: pickee_limit; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.pickee_limit (
    limit_id bigint NOT NULL,
    pickee_limit_id bigint NOT NULL,
    pickee_id bigint NOT NULL
);


ALTER TABLE public.pickee_limit OWNER TO jdog;

--
-- Name: pickee_stat; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.pickee_stat (
    pickee_stat_id bigint NOT NULL,
    pickee_id bigint NOT NULL,
    previous_rank integer NOT NULL,
    stat_field_id bigint NOT NULL
);


ALTER TABLE public.pickee_stat OWNER TO jdog;

--
-- Name: pickee_stat_period; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.pickee_stat_period (
    pickee_stat_id bigint NOT NULL,
    pickee_stat_period_id bigint NOT NULL,
    value double precision NOT NULL,
    period integer NOT NULL
);


ALTER TABLE public.pickee_stat_period OWNER TO jdog;

--
-- Name: points; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.points (
    points_id bigint NOT NULL,
    points_field_id bigint NOT NULL,
    result_id bigint NOT NULL,
    value double precision NOT NULL
);


ALTER TABLE public.points OWNER TO jdog;

--
-- Name: resultu; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.resultu (
    is_team_one boolean NOT NULL,
    resultu_id bigint NOT NULL,
    pickee_id bigint NOT NULL,
    match_id bigint NOT NULL
);


ALTER TABLE public.resultu OWNER TO jdog;

--
-- Name: s_game_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_game_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_game_id OWNER TO jdog;

--
-- Name: s_league_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_league_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_league_id OWNER TO jdog;

--
-- Name: s_league_prize_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_league_prize_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_league_prize_id OWNER TO jdog;

--
-- Name: s_league_stat_field_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_league_stat_field_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_league_stat_field_id OWNER TO jdog;

--
-- Name: s_league_user_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_league_user_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_league_user_id OWNER TO jdog;

--
-- Name: s_league_user_stat_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_league_user_stat_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_league_user_stat_id OWNER TO jdog;

--
-- Name: s_league_user_stat_period_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_league_user_stat_period_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_league_user_stat_period_id OWNER TO jdog;

--
-- Name: s_limit_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_limit_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_limit_id OWNER TO jdog;

--
-- Name: s_limit_type_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_limit_type_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_limit_type_id OWNER TO jdog;

--
-- Name: s_matchu_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_matchu_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_matchu_id OWNER TO jdog;

--
-- Name: s_period_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_period_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_period_id OWNER TO jdog;

--
-- Name: s_pickee_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_pickee_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_pickee_id OWNER TO jdog;

--
-- Name: s_pickee_limit_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_pickee_limit_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_pickee_limit_id OWNER TO jdog;

--
-- Name: s_pickee_stat_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_pickee_stat_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_pickee_stat_id OWNER TO jdog;

--
-- Name: s_pickee_stat_period_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_pickee_stat_period_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_pickee_stat_period_id OWNER TO jdog;

--
-- Name: s_points_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_points_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_points_id OWNER TO jdog;

--
-- Name: s_resultu_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_resultu_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_resultu_id OWNER TO jdog;

--
-- Name: s_team_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_team_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_team_id OWNER TO jdog;

--
-- Name: s_team_pickee_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_team_pickee_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_team_pickee_id OWNER TO jdog;

--
-- Name: s_transfer_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_transfer_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_transfer_id OWNER TO jdog;

--
-- Name: s_useru_id; Type: SEQUENCE; Schema: public; Owner: jdog
--

CREATE SEQUENCE public.s_useru_id
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER TABLE public.s_useru_id OWNER TO jdog;

--
-- Name: stat_field; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.stat_field (
    name character varying(128) NOT NULL,
    league_id bigint NOT NULL,
    stat_field_id bigint NOT NULL
);


ALTER TABLE public.stat_field OWNER TO jdog;

--
-- Name: team_pickee; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.team_pickee (
    team_id bigint NOT NULL,
    team_pickee_id bigint NOT NULL,
    pickee_id bigint NOT NULL,
    timespan tstzrange
);


ALTER TABLE public.team_pickee OWNER TO jdog;

--
-- Name: transfer; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.transfer (
    is_buy boolean NOT NULL,
    scheduled_for timestamp without time zone NOT NULL,
    transfer_id bigint NOT NULL,
    price numeric(3,1) NOT NULL,
    processed boolean NOT NULL,
    pickee_id bigint NOT NULL,
    was_wildcard boolean NOT NULL,
    league_user_id bigint NOT NULL,
    time_made timestamp without time zone NOT NULL
);


ALTER TABLE public.transfer OWNER TO jdog;

--
-- Name: useru; Type: TABLE; Schema: public; Owner: jdog
--

CREATE TABLE public.useru (
    external_id bigint NOT NULL,
    username character varying(128) NOT NULL,
    user_id bigint NOT NULL
);


ALTER TABLE public.useru OWNER TO jdog;

--
-- Name: api_user api_user_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.api_user
    ADD CONSTRAINT api_user_pkey PRIMARY KEY (api_user_id);


--
-- Name: game game_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.game
    ADD CONSTRAINT game_pkey PRIMARY KEY (game_id);


--
-- Name: league league_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league
    ADD CONSTRAINT league_pkey PRIMARY KEY (league_id);


--
-- Name: league_prize league_prize_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_prize
    ADD CONSTRAINT league_prize_pkey PRIMARY KEY (league_prize_id);


--
-- Name: stat_field league_stat_field_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.stat_field
    ADD CONSTRAINT league_stat_field_pkey PRIMARY KEY (stat_field_id);


--
-- Name: league_user league_user_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user
    ADD CONSTRAINT league_user_pkey PRIMARY KEY (league_user_id);


--
-- Name: league_user_stat_period league_user_stat_period_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user_stat_period
    ADD CONSTRAINT league_user_stat_period_pkey PRIMARY KEY (league_user_stat_period_id);


--
-- Name: league_user_stat league_user_stat_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user_stat
    ADD CONSTRAINT league_user_stat_pkey PRIMARY KEY (league_user_stat_id);


--
-- Name: limit limit_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public."limit"
    ADD CONSTRAINT limit_pkey PRIMARY KEY (limit_id);


--
-- Name: limit_type limit_type_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.limit_type
    ADD CONSTRAINT limit_type_pkey PRIMARY KEY (limit_type_id);


--
-- Name: matchu matchu_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.matchu
    ADD CONSTRAINT matchu_pkey PRIMARY KEY (match_id);


--
-- Name: period period_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.period
    ADD CONSTRAINT period_pkey PRIMARY KEY (period_id);


--
-- Name: pickee_limit pickee_limit_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_limit
    ADD CONSTRAINT pickee_limit_pkey PRIMARY KEY (pickee_limit_id);


--
-- Name: pickee pickee_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee
    ADD CONSTRAINT pickee_pkey PRIMARY KEY (pickee_id);


--
-- Name: pickee_stat_period pickee_stat_period_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_stat_period
    ADD CONSTRAINT pickee_stat_period_pkey PRIMARY KEY (pickee_stat_period_id);


--
-- Name: pickee_stat pickee_stat_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_stat
    ADD CONSTRAINT pickee_stat_pkey PRIMARY KEY (pickee_stat_id);


--
-- Name: points points_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.points
    ADD CONSTRAINT points_pkey PRIMARY KEY (points_id);


--
-- Name: resultu resultu_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.resultu
    ADD CONSTRAINT resultu_pkey PRIMARY KEY (resultu_id);


--
-- Name: team_pickee team_pickee_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.team_pickee
    ADD CONSTRAINT team_pickee_pkey PRIMARY KEY (team_pickee_id);


--
-- Name: transfer transfer_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.transfer
    ADD CONSTRAINT transfer_pkey PRIMARY KEY (transfer_id);


--
-- Name: useru useru_pkey; Type: CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.useru
    ADD CONSTRAINT useru_pkey PRIMARY KEY (user_id);


--
-- Name: idx38c10f94; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idx38c10f94 ON public.pickee_stat_period USING btree (period, pickee_stat_id);


--
-- Name: idx585b080c; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idx585b080c ON public.pickee USING btree (name, league_id);


--
-- Name: idx6372089a; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idx6372089a ON public.period USING btree (value, league_id);


--
-- Name: idx65e608c9; Type: INDEX; Schema: public; Owner: jdog
--

CREATE INDEX idx65e608c9 ON public.league_prize USING btree (league_id);


--
-- Name: idx78760987; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idx78760987 ON public."limit" USING btree (limit_type_id, name);


--
-- Name: idx7fe709ca; Type: INDEX; Schema: public; Owner: jdog
--

CREATE INDEX idx7fe709ca ON public.league USING btree (is_private, game_id);


--
-- Name: idx830a09db; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idx830a09db ON public.limit_type USING btree (league_id, name);


--
-- Name: idx8d2f0a45; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idx8d2f0a45 ON public.resultu USING btree (pickee_id, match_id);


--
-- Name: idx8ea40a7e; Type: INDEX; Schema: public; Owner: jdog
--

CREATE INDEX idx8ea40a7e ON public.useru USING btree (external_id, username);


--
-- Name: idx8fb61693; Type: INDEX; Schema: public; Owner: jdog
--

CREATE INDEX idx8fb61693 ON public.transfer USING btree (league_user_id, pickee_id, scheduled_for, processed);


--
-- Name: idxa1410b0b; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxa1410b0b ON public.matchu USING btree (external_id, league_id);


--
-- Name: idxa1c2120f; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxa1c2120f ON public.league_user_stat USING btree (stat_field_id, league_user_id);


--
-- Name: idxaa6b0b42; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxaa6b0b42 ON public.team_pickee USING btree (team_id, pickee_id);


--
-- Name: idxad130b76; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxad130b76 ON public.league_user USING btree (league_id, user_id);


--
-- Name: idxc5050c32; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxc5050c32 ON public.pickee_limit USING btree (limit_id, pickee_id);


--
-- Name: idxd2c90c8c; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxd2c90c8c ON public.stat_field USING btree (league_id, name);


--
-- Name: idxd6e00cef; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxd6e00cef ON public.points USING btree (result_id, points_field_id);


--
-- Name: idxf09813d4; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxf09813d4 ON public.league_user_stat_period USING btree (period, league_user_stat_id);


--
-- Name: idxfb4e0dcf; Type: INDEX; Schema: public; Owner: jdog
--

CREATE UNIQUE INDEX idxfb4e0dcf ON public.pickee_stat USING btree (stat_field_id, pickee_id);


--
-- Name: league leagueFK10; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league
    ADD CONSTRAINT "leagueFK10" FOREIGN KEY (api_key) REFERENCES public.api_user(api_user_id);


--
-- Name: league leagueFK25; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league
    ADD CONSTRAINT "leagueFK25" FOREIGN KEY (game_id) REFERENCES public.game(game_id);


--
-- Name: league_prize league_prizeFK9; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_prize
    ADD CONSTRAINT "league_prizeFK9" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: stat_field league_stat_fieldFK11; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.stat_field
    ADD CONSTRAINT "league_stat_fieldFK11" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: league_user league_userFK1; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user
    ADD CONSTRAINT "league_userFK1" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: league_user league_userFK2; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user
    ADD CONSTRAINT "league_userFK2" FOREIGN KEY (user_id) REFERENCES public.useru(user_id);


--
-- Name: league_user_stat league_user_statFK7; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user_stat
    ADD CONSTRAINT "league_user_statFK7" FOREIGN KEY (league_user_id) REFERENCES public.league_user(league_user_id);


--
-- Name: league_user_stat_period league_user_stat_periodFK8; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.league_user_stat_period
    ADD CONSTRAINT "league_user_stat_periodFK8" FOREIGN KEY (league_user_stat_id) REFERENCES public.league_user_stat(league_user_stat_id);


--
-- Name: limit limitFK4; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public."limit"
    ADD CONSTRAINT "limitFK4" FOREIGN KEY (limit_type_id) REFERENCES public.limit_type(limit_type_id);


--
-- Name: limit_type limit_typeFK3; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.limit_type
    ADD CONSTRAINT "limit_typeFK3" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: matchu matchuFK14; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.matchu
    ADD CONSTRAINT "matchuFK14" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: period periodFK13; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.period
    ADD CONSTRAINT "periodFK13" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: pickee pickeeFK12; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee
    ADD CONSTRAINT "pickeeFK12" FOREIGN KEY (league_id) REFERENCES public.league(league_id);


--
-- Name: pickee_limit pickee_limitFK5; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_limit
    ADD CONSTRAINT "pickee_limitFK5" FOREIGN KEY (pickee_id) REFERENCES public.pickee(pickee_id);


--
-- Name: pickee_limit pickee_limitFK6; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_limit
    ADD CONSTRAINT "pickee_limitFK6" FOREIGN KEY (limit_id) REFERENCES public."limit"(limit_id);


--
-- Name: pickee_stat pickee_statFK16; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_stat
    ADD CONSTRAINT "pickee_statFK16" FOREIGN KEY (pickee_id) REFERENCES public.pickee(pickee_id);


--
-- Name: pickee_stat_period pickee_stat_periodFK17; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.pickee_stat_period
    ADD CONSTRAINT "pickee_stat_periodFK17" FOREIGN KEY (pickee_stat_id) REFERENCES public.pickee_stat(pickee_stat_id);


--
-- Name: points pointsFK23; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.points
    ADD CONSTRAINT "pointsFK23" FOREIGN KEY (result_id) REFERENCES public.resultu(resultu_id);


--
-- Name: points pointsFK24; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.points
    ADD CONSTRAINT "pointsFK24" FOREIGN KEY (points_field_id) REFERENCES public.stat_field(stat_field_id);


--
-- Name: resultu resultuFK18; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.resultu
    ADD CONSTRAINT "resultuFK18" FOREIGN KEY (pickee_id) REFERENCES public.pickee(pickee_id);


--
-- Name: team_pickee team_pickeeFK15; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.team_pickee
    ADD CONSTRAINT "team_pickeeFK15" FOREIGN KEY (pickee_id) REFERENCES public.pickee(pickee_id);


--
-- Name: transfer transferFK19; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.transfer
    ADD CONSTRAINT "transferFK19" FOREIGN KEY (pickee_id) REFERENCES public.pickee(pickee_id);


--
-- Name: transfer transferFK20; Type: FK CONSTRAINT; Schema: public; Owner: jdog
--

ALTER TABLE ONLY public.transfer
    ADD CONSTRAINT "transferFK20" FOREIGN KEY (league_user_id) REFERENCES public.league_user(league_user_id);


--
-- PostgreSQL database dump complete
--

